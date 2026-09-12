"""SQLite adapter for the CONTRACTS 3 store port.

Identity is `(buildSha, className, methodDesc)` -- never a hash of runtime
class bytes (CONTRACTS 1 / A14 defect 1). Probe indices are build-time
assigned, so `idx` is stable and can be keyed on directly.
"""

import json
import sqlite3
import threading
from collections.abc import Callable, Iterator, Mapping
from contextlib import contextmanager
from datetime import UTC, datetime

from ax_server.store.bitset import is_set, newly_set, or_merge, set_bits
from ax_server.store.errors import SchemaMismatch
from ax_server.store.models import (
    AgentHealth,
    IngestWindow,
    Window,
    epoch_ms,
    from_epoch_ms,
)
from ax_server.store.port import IngestAudit, Store, WindowAttribution

__all__ = ["SqliteStore"]

_SCHEMA = """
CREATE TABLE IF NOT EXISTS windows (
    window_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    build_sha       TEXT NOT NULL,
    artifact        TEXT NOT NULL,
    instance_id     TEXT NOT NULL,
    window_start_ms INTEGER NOT NULL,
    window_end_ms   INTEGER NOT NULL,
    degraded        INTEGER NOT NULL,
    environment     TEXT NOT NULL,
    production      INTEGER NOT NULL,
    test_tainted    INTEGER NOT NULL,
    health_json     TEXT NOT NULL,
    received_ms     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS windows_build ON windows(build_sha, window_start_ms);

CREATE TABLE IF NOT EXISTS class_coverage (
    build_sha   TEXT NOT NULL,
    cls         TEXT NOT NULL,
    schema_hash TEXT NOT NULL,
    probes      BLOB NOT NULL,
    PRIMARY KEY (build_sha, cls)
);

CREATE TABLE IF NOT EXISTS probe_seen (
    build_sha    TEXT NOT NULL,
    cls          TEXT NOT NULL,
    idx          INTEGER NOT NULL,
    first_seen_ms INTEGER NOT NULL,
    last_seen_ms  INTEGER NOT NULL,
    PRIMARY KEY (build_sha, cls, idx)
);

-- C10: tracked SEPARATELY from probe bits so that "never loaded" and
-- "loaded but never invoked" are different observations.
CREATE TABLE IF NOT EXISTS class_loaded (
    build_sha      TEXT NOT NULL,
    cls            TEXT NOT NULL,
    first_loaded_ms INTEGER NOT NULL,
    last_loaded_ms  INTEGER NOT NULL,
    load_windows    INTEGER NOT NULL,
    PRIMARY KEY (build_sha, cls)
);

CREATE TABLE IF NOT EXISTS tier2 (
    window_id     INTEGER NOT NULL,
    build_sha     TEXT NOT NULL,
    cls           TEXT NOT NULL,
    idx           INTEGER NOT NULL,
    window_end_ms INTEGER NOT NULL,
    calls         INTEGER NOT NULL,
    errors        INTEGER NOT NULL,
    error_types   TEXT NOT NULL,
    buckets       TEXT NOT NULL,
    bucket_scheme TEXT NOT NULL,
    PRIMARY KEY (window_id, cls, idx)
);
CREATE INDEX IF NOT EXISTS tier2_lookup ON tier2(build_sha, cls, idx, window_end_ms);

CREATE TABLE IF NOT EXISTS ingest_rejects (
    reject_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    reason      TEXT NOT NULL,
    build_sha   TEXT,
    artifact    TEXT,
    instance_id TEXT,
    detail      TEXT NOT NULL,
    when_ms     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS rejects_build ON ingest_rejects(build_sha, when_ms);
"""


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


class SqliteStore(Store, WindowAttribution, IngestAudit):
    """The one adapter we can actually exercise locally (TOOLCHAIN.md 4).

    Thread-safe via a single lock around a single connection. That is enough
    for a collector doing one flush per instance per minute; it is explicitly
    not a high-write design.
    """

    def __init__(
        self,
        path: str = ":memory:",
        *,
        clock: Callable[[], datetime] = _utcnow,
    ) -> None:
        self._path = path
        self._clock = clock
        self._lock = threading.RLock()
        self._attributed_at: datetime | None = None
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        with self._lock:
            self._conn.executescript(_SCHEMA)
            self._conn.commit()

    # -- lifecycle -------------------------------------------------------

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def __enter__(self) -> "SqliteStore":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # -- WindowAttribution ----------------------------------------------

    @contextmanager
    def attribute_to(self, when: datetime) -> Iterator[None]:
        if when.tzinfo is None:
            raise ValueError("attribute_to requires an aware UTC datetime")
        with self._lock:
            previous = self._attributed_at
            self._attributed_at = when
            try:
                yield
            finally:
                self._attributed_at = previous

    def _now_ms(self) -> int:
        return epoch_ms(self._attributed_at or self._clock())  # type: ignore[return-value]

    # -- Store (CONTRACTS 3) --------------------------------------------

    def record_window(self, w: IngestWindow) -> None:
        received = w.received_at or self._clock()
        health = w.agent_health
        payload = {
            "transformFailures": health.transform_failures,
            "classesSkipped": dict(health.classes_skipped),
            "ringDropped": health.ring_dropped,
            "clockNs": health.clock_ns,
            "clockDegraded": health.clock_degraded,
            "degraded": health.degraded,
        }
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO windows (build_sha, artifact, instance_id, window_start_ms,"
                " window_end_ms, degraded, environment, production, test_tainted,"
                " health_json, received_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (
                    w.build_sha,
                    w.artifact,
                    w.instance_id,
                    w.window_start_ms,
                    w.window_end_ms,
                    int(health.degraded),
                    w.environment,
                    int(w.production),
                    int(w.test_tainted),
                    json.dumps(payload, sort_keys=True),
                    epoch_ms(received),
                ),
            )
            window_id = int(cur.lastrowid or 0)
            loaded_ms = w.window_end_ms
            for cls in w.classes_loaded:
                self._conn.execute(
                    "INSERT INTO class_loaded (build_sha, cls, first_loaded_ms,"
                    " last_loaded_ms, load_windows) VALUES (?,?,?,?,1)"
                    " ON CONFLICT(build_sha, cls) DO UPDATE SET"
                    "   first_loaded_ms=MIN(first_loaded_ms, excluded.first_loaded_ms),"
                    "   last_loaded_ms=MAX(last_loaded_ms, excluded.last_loaded_ms),"
                    "   load_windows=load_windows+1",
                    (w.build_sha, cls, loaded_ms, loaded_ms),
                )
            for rec in w.tier2:
                self._conn.execute(
                    "INSERT OR REPLACE INTO tier2 (window_id, build_sha, cls, idx,"
                    " window_end_ms, calls, errors, error_types, buckets, bucket_scheme)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    (
                        window_id,
                        w.build_sha,
                        rec.cls,
                        rec.idx,
                        w.window_end_ms,
                        rec.calls,
                        rec.errors,
                        json.dumps(dict(rec.error_types), sort_keys=True),
                        json.dumps(list(rec.buckets)),
                        rec.bucket_scheme,
                    ),
                )
            self._conn.commit()

    def merge_coverage(self, build_sha: str, cls: str, schema_hash: str, probes: bytes) -> None:
        now_ms = self._now_ms()
        with self._lock:
            row = self._conn.execute(
                "SELECT schema_hash, probes FROM class_coverage WHERE build_sha=? AND cls=?",
                (build_sha, cls),
            ).fetchone()
            if row is None:
                existing = b""
            else:
                stored_hash = str(row["schema_hash"])
                if stored_hash != schema_hash:
                    # Loud, and no write of any kind. Silently merging would
                    # misattribute probes; silently reporting zero would invent
                    # dead code. Both are the JaCoCo failure mode (A14).
                    raise SchemaMismatch(build_sha, cls, stored_hash, schema_hash)
                existing = bytes(row["probes"])

            merged = or_merge(existing, probes)
            fresh = newly_set(existing, probes)
            self._conn.execute(
                "INSERT INTO class_coverage (build_sha, cls, schema_hash, probes)"
                " VALUES (?,?,?,?) ON CONFLICT(build_sha, cls) DO UPDATE SET probes=excluded.probes",
                (build_sha, cls, schema_hash, merged),
            )
            for idx in fresh:
                self._conn.execute(
                    "INSERT INTO probe_seen (build_sha, cls, idx, first_seen_ms, last_seen_ms)"
                    " VALUES (?,?,?,?,?)",
                    (build_sha, cls, idx, now_ms, now_ms),
                )
            already = [idx for idx in set_bits(probes) if is_set(existing, idx)]
            for idx in already:
                self._conn.execute(
                    "UPDATE probe_seen SET last_seen_ms=MAX(last_seen_ms, ?)"
                    " WHERE build_sha=? AND cls=? AND idx=?",
                    (now_ms, build_sha, cls, idx),
                )
            self._conn.commit()

    def first_seen(self, build_sha: str, cls: str, idx: int) -> datetime | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT first_seen_ms FROM probe_seen WHERE build_sha=? AND cls=? AND idx=?",
                (build_sha, cls, idx),
            ).fetchone()
        return from_epoch_ms(int(row["first_seen_ms"])) if row else None

    def last_seen(self, build_sha: str, cls: str, idx: int) -> datetime | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT last_seen_ms FROM probe_seen WHERE build_sha=? AND cls=? AND idx=?",
                (build_sha, cls, idx),
            ).fetchone()
        return from_epoch_ms(int(row["last_seen_ms"])) if row else None

    def coverage(self, build_sha: str, cls: str) -> bytes | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT probes FROM class_coverage WHERE build_sha=? AND cls=?",
                (build_sha, cls),
            ).fetchone()
        return bytes(row["probes"]) if row else None

    def observed_windows(self, build_sha: str) -> list[Window]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM windows WHERE build_sha=? ORDER BY window_start_ms, window_id",
                (build_sha,),
            ).fetchall()
        return [self._window(row) for row in rows]

    def class_loaded_ever(self, build_sha: str, cls: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT 1 FROM class_loaded WHERE build_sha=? AND cls=?",
                (build_sha, cls),
            ).fetchone()
        return row is not None

    def tier2_buckets(self, build_sha: str, cls: str, idx: int, since: datetime) -> list[int]:
        since_ms = epoch_ms(since)
        with self._lock:
            rows = self._conn.execute(
                "SELECT buckets FROM tier2 WHERE build_sha=? AND cls=? AND idx=?"
                " AND window_end_ms>=?",
                (build_sha, cls, idx, since_ms),
            ).fetchall()
        total: list[int] = []
        for row in rows:
            buckets = json.loads(row["buckets"])
            if len(buckets) > len(total):
                total.extend([0] * (len(buckets) - len(total)))
            for i, count in enumerate(buckets):
                total[i] += int(count)
        return total

    # -- IngestAudit -----------------------------------------------------

    def record_reject(
        self,
        *,
        reason: str,
        build_sha: str | None,
        artifact: str | None,
        instance_id: str | None,
        detail: str,
        when: datetime,
    ) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO ingest_rejects (reason, build_sha, artifact, instance_id,"
                " detail, when_ms) VALUES (?,?,?,?,?,?)",
                (reason, build_sha, artifact, instance_id, detail, epoch_ms(when)),
            )
            self._conn.commit()

    def rejects(self, build_sha: str | None = None, limit: int = 100) -> list[dict[str, object]]:
        sql = "SELECT * FROM ingest_rejects"
        args: tuple[object, ...] = ()
        if build_sha is not None:
            sql += " WHERE build_sha=?"
            args = (build_sha,)
        sql += " ORDER BY reject_id DESC LIMIT ?"
        args = (*args, limit)
        with self._lock:
            rows = self._conn.execute(sql, args).fetchall()
        return [
            {
                "reason": row["reason"],
                "buildSha": row["build_sha"],
                "artifact": row["artifact"],
                "instanceId": row["instance_id"],
                "detail": row["detail"],
                "at": from_epoch_ms(int(row["when_ms"])).isoformat(),  # type: ignore[union-attr]
            }
            for row in rows
        ]

    # -- adapter extras used by the read-only API ------------------------

    def classes_with_coverage(self, build_sha: str) -> list[str]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT cls FROM class_coverage WHERE build_sha=? ORDER BY cls",
                (build_sha,),
            ).fetchall()
        return [str(row["cls"]) for row in rows]

    def loaded_classes(self, build_sha: str) -> list[str]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT cls FROM class_loaded WHERE build_sha=? ORDER BY cls",
                (build_sha,),
            ).fetchall()
        return [str(row["cls"]) for row in rows]

    def tier2_rows(self, build_sha: str, since: datetime) -> list[dict[str, object]]:
        """Aggregated tier-2 counters per (class, idx) since `since`."""
        since_ms = epoch_ms(since)
        with self._lock:
            rows = self._conn.execute(
                "SELECT cls, idx, SUM(calls) AS calls, SUM(errors) AS errors"
                " FROM tier2 WHERE build_sha=? AND window_end_ms>=?"
                " GROUP BY cls, idx ORDER BY SUM(calls) DESC",
                (build_sha, since_ms),
            ).fetchall()
        return [
            {"class": str(r["cls"]), "idx": int(r["idx"]),
             "calls": int(r["calls"] or 0), "errors": int(r["errors"] or 0)}
            for r in rows
        ]

    def builds(self) -> list[str]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT DISTINCT build_sha FROM windows ORDER BY build_sha"
            ).fetchall()
        return [str(row["build_sha"]) for row in rows]

    # -- internals -------------------------------------------------------

    @staticmethod
    def _window(row: sqlite3.Row) -> Window:
        health_raw: Mapping[str, object] = json.loads(row["health_json"])
        skipped = health_raw.get("classesSkipped") or {}
        health = AgentHealth(
            transform_failures=int(health_raw.get("transformFailures", 0)),  # type: ignore[arg-type]
            classes_skipped={str(k): int(v) for k, v in dict(skipped).items()},
            ring_dropped=int(health_raw.get("ringDropped", 0)),  # type: ignore[arg-type]
            clock_ns=int(health_raw.get("clockNs", 0)),  # type: ignore[arg-type]
            clock_degraded=bool(health_raw.get("clockDegraded", False)),
            degraded=bool(health_raw.get("degraded", False)),
            raw=health_raw,
        )
        return Window(
            window_id=int(row["window_id"]),
            build_sha=str(row["build_sha"]),
            artifact=str(row["artifact"]),
            instance_id=str(row["instance_id"]),
            start=from_epoch_ms(int(row["window_start_ms"])),  # type: ignore[arg-type]
            end=from_epoch_ms(int(row["window_end_ms"])),  # type: ignore[arg-type]
            degraded=bool(row["degraded"]),
            agent_health=health,
            environment=str(row["environment"]),
            production=bool(row["production"]),
            test_tainted=bool(row["test_tainted"]),
            received_at=from_epoch_ms(int(row["received_ms"])),  # type: ignore[arg-type]
        )
