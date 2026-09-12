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
    ERROR_TYPES_UNAVAILABLE,
    AgentHealth,
    EdgeAggregate,
    EdgeEndpoint,
    EdgeHealth,
    IngestWindow,
    Window,
    epoch_ms,
    from_epoch_ms,
)
from ax_server.store.port import (
    EdgeStore,
    IngestAudit,
    ProbeInstallStore,
    Store,
    Tier2ErrorStore,
    WindowAttribution,
)

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
    received_ms     INTEGER NOT NULL,
    -- BUG #22b: CONTRACTS 2 `agentHealth.livenessEvidence`, as concluded by
    -- the collector. A non-production window is now STORED with this 0
    -- instead of being 403'd away, so this column is what keeps it out of
    -- every evidence query. DEFAULT 1 so a row written before this column
    -- existed keeps the meaning it had, where `production` was the only gate.
    liveness_evidence INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS windows_build ON windows(build_sha, window_start_ms);

CREATE TABLE IF NOT EXISTS class_coverage (
    build_sha   TEXT NOT NULL,
    cls         TEXT NOT NULL,
    schema_hash TEXT NOT NULL,
    probes      BLOB NOT NULL,
    PRIMARY KEY (build_sha, cls)
);

-- BUG #18: the probe array is sized to the manifest's `probeCount` -- every
-- probe-eligible method -- but the emitter installs a probe at only SOME of
-- those indices. An index with no probe is NEVER WRITTEN BY ANYTHING, so its
-- bit is permanently zero, and shipping it alongside genuinely-zero bits made
-- a JVM with `ax.tier1.enabled=false` look like a codebase full of dead code.
--
-- Merged as a bitwise OR across pods and windows, EXACTLY as coverage is:
-- different JVMs legitimately install different probe sets (a class-file-<55
-- fallback here, a `frameEmissionUnsupported` method there), and the union is
-- the conservative answer -- "some JVM could have observed this index".
--
-- A separate table rather than a column on `class_coverage` so that ROW
-- EXISTENCE carries the "a mask was reported at all" fact: an all-zero mask
-- is a positive report and an absent row is silence, and an all-zero mask can
-- be zero bytes wide.
CREATE TABLE IF NOT EXISTS class_probes_installed (
    build_sha    TEXT NOT NULL,
    cls          TEXT NOT NULL,
    schema_hash  TEXT NOT NULL,
    installed    BLOB NOT NULL,
    windows      INTEGER NOT NULL,
    first_ms     INTEGER NOT NULL,
    last_ms      INTEGER NOT NULL,
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
    -- BUG #24: exception class NAMES, never the window-local `errorClasses`
    -- ids the wire carries. Ids are valid only inside the window that shipped
    -- them (id 1 in one window is unrelated to id 1 in the next), so storing
    -- one would be storing a number whose meaning expired at the next flush.
    error_types   TEXT NOT NULL,
    buckets       TEXT NOT NULL,
    bucket_scheme TEXT NOT NULL,
    -- Which shape the names came from: `errorsByClass` (v4, resolved here),
    -- `errorTypes` (pre-v4, names on the wire) or `unavailable` (no breakdown
    -- arrived -- LEGAL with errors > 0, and reported as "types unavailable").
    error_types_source TEXT NOT NULL DEFAULT 'unavailable',
    -- `errors - sum(errorsByClass)`. Legitimately non-zero: 254-class table,
    -- id 255 = overflow, `errors` incremented unconditionally. Stored so the
    -- remainder survives aggregation instead of being silently absorbed.
    unattributed_errors INTEGER NOT NULL DEFAULT 0,
    -- Of that remainder, how much was filed under an id the window's own name
    -- table did not name. Never given an invented class name.
    unresolved_id_errors INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (window_id, cls, idx)
);
CREATE INDEX IF NOT EXISTS tier2_lookup ON tier2(build_sha, cls, idx, window_end_ms);

-- SCOPE-v3: the sampled runtime call-edge tier. Per-window rows are kept
-- alongside the aggregate so a count can always be traced back to the window
-- (and therefore the sample rate) that produced it.
CREATE TABLE IF NOT EXISTS window_edges (
    window_id     INTEGER NOT NULL,
    build_sha     TEXT NOT NULL,
    caller_cls    TEXT NOT NULL,
    caller_idx    INTEGER NOT NULL,
    callee_cls    TEXT NOT NULL,
    callee_idx    INTEGER NOT NULL,
    observations  INTEGER NOT NULL,
    sample_rate   INTEGER NOT NULL,
    window_end_ms INTEGER NOT NULL,
    PRIMARY KEY (window_id, caller_cls, caller_idx, callee_cls, callee_idx)
);
CREATE INDEX IF NOT EXISTS window_edges_build ON window_edges(build_sha, window_end_ms);

-- (build_sha, caller, callee) -> SUMMED sampled observations. Additive, NOT
-- an OR: `edges[].count` is a per-window delta, `coverage[].probes` is an
-- accumulated bitset. Same table, opposite semantics, never conflated.
CREATE TABLE IF NOT EXISTS edge_agg (
    build_sha       TEXT NOT NULL,
    caller_cls      TEXT NOT NULL,
    caller_idx      INTEGER NOT NULL,
    callee_cls      TEXT NOT NULL,
    callee_idx      INTEGER NOT NULL,
    observations    INTEGER NOT NULL,
    windows         INTEGER NOT NULL,
    first_seen_ms   INTEGER NOT NULL,
    last_seen_ms    INTEGER NOT NULL,
    sample_rate_min INTEGER NOT NULL,
    sample_rate_max INTEGER NOT NULL,
    PRIMARY KEY (build_sha, caller_cls, caller_idx, callee_cls, callee_idx)
);
-- Both directions are first-class queries ("who calls me" for blast radius,
-- "what do I call" for tracing a path), so both are indexed. The caller
-- index is not the PRIMARY KEY prefix by accident -- it is named so the
-- intent survives a schema edit.
CREATE INDEX IF NOT EXISTS edge_agg_caller
    ON edge_agg(build_sha, caller_cls, caller_idx);
CREATE INDEX IF NOT EXISTS edge_agg_callee
    ON edge_agg(build_sha, callee_cls, callee_idx);
CREATE INDEX IF NOT EXISTS edge_agg_hot ON edge_agg(build_sha, observations DESC);

-- sample_rate -> windows seen at it. Returned with every count, because a
-- count without its rate is uninterpretable (CONTRACTS 2 v3).
CREATE TABLE IF NOT EXISTS edge_sample_rates (
    build_sha   TEXT NOT NULL,
    sample_rate INTEGER NOT NULL,
    windows     INTEGER NOT NULL,
    PRIMARY KEY (build_sha, sample_rate)
);

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

#: `table -> {column: DDL}` for columns added after a table first shipped.
#: `CREATE TABLE IF NOT EXISTS` is a no-op on an existing database, so a
#: deployment that already has `auxin.sqlite` would otherwise start raising
#: `no such column` on every ingest. Additive only, every column with a
#: DEFAULT that preserves the pre-migration meaning.
_MIGRATIONS: dict[str, dict[str, str]] = {
    # BUG #22b
    "windows": {"liveness_evidence": "INTEGER NOT NULL DEFAULT 1"},
    # BUG #24
    "tier2": {
        "error_types_source": "TEXT NOT NULL DEFAULT 'unavailable'",
        "unattributed_errors": "INTEGER NOT NULL DEFAULT 0",
        "unresolved_id_errors": "INTEGER NOT NULL DEFAULT 0",
    },
}


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


def _int(raw: Mapping[str, object], key: str) -> int:
    value = raw.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0
    return int(value)


def _edge_health(raw: Mapping[str, object]) -> EdgeHealth:
    """Rebuild the v3 edge counters from a persisted `agentHealth` blob.

    Every field defaults to 0/false, so a window recorded by a v2 producer
    reads back as "the tier said nothing" -- which is the honest answer, and
    is NOT the same as "the tier ran and found nothing".
    """
    return EdgeHealth(
        enabled=bool(raw.get("edgesEnabled", False)),
        sample_rate=_int(raw, "edgesSampleRate"),
        sampled_roots=_int(raw, "edgesSampledRoots"),
        recorded=_int(raw, "edgesRecorded"),
        dropped=_int(raw, "edgesDropped"),
        truncated_depth=_int(raw, "edgesTruncatedDepth"),
        truncated_root=_int(raw, "edgesTruncatedRoot"),
        truncated_distinct=_int(raw, "edgesTruncatedDistinct"),
        tier_failures=_int(raw, "edgeTierFailures"),
        traces_reaped=_int(raw, "edgeTracesReaped"),
    )


def _fold_error_classes(rows: list[sqlite3.Row]) -> dict[str, object]:
    """Sum stored per-window exception breakdowns into one answer (bug #24).

    Three invariants, and every one of them is a rule CONTRACTS 2 v4 states
    explicitly:

    * counts are summed PER NAME. Ids were resolved in the window that carried
      them, so summing names across windows is the only sound operation --
      summing ids across windows would add together unrelated classes.
    * `unattributed` is summed as its own number and never folded into
      `byClass`. It is `errors - sum(errorsByClass)`, which is legal, and it
      has no class name.
    * `source` is `unavailable` only when NO contributing window carried a
      breakdown. One window with names and nine without still means we know
      something, and the honest report is "some types, plus a remainder".
    """
    by_class: dict[str, int] = {}
    errors = 0
    unattributed = 0
    unresolved = 0
    sources: set[str] = set()
    for row in rows:
        errors += int(row["errors"] or 0)
        unattributed += int(row["unattributed_errors"] or 0)
        unresolved += int(row["unresolved_id_errors"] or 0)
        sources.add(str(row["error_types_source"] or ERROR_TYPES_UNAVAILABLE))
        for name, count in dict(json.loads(row["error_types"] or "{}")).items():
            by_class[str(name)] = by_class.get(str(name), 0) + int(count)
    named = sorted(sources - {ERROR_TYPES_UNAVAILABLE})
    source = "+".join(named) if named else ERROR_TYPES_UNAVAILABLE
    return {
        "byClass": dict(sorted(by_class.items(), key=lambda p: (-p[1], p[0]))),
        "attributed": sum(by_class.values()),
        "errors": errors,
        "unattributed": unattributed,
        "unresolvedIds": unresolved,
        "source": source,
        "typesAvailable": source != ERROR_TYPES_UNAVAILABLE,
        "windows": len(rows),
    }


class SqliteStore(
    Store, WindowAttribution, IngestAudit, EdgeStore, ProbeInstallStore, Tier2ErrorStore
):
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
            self._migrate()
            self._conn.commit()

    def _migrate(self) -> None:
        """Add columns that postdate an existing database file.

        Called under the constructor's lock, right after the schema script.
        Idempotent: it reads `PRAGMA table_info` and adds only what is
        missing, so a fresh `:memory:` store finds nothing to do.
        """
        for table, columns in _MIGRATIONS.items():
            existing = {
                str(row["name"])
                for row in self._conn.execute(f"PRAGMA table_info({table})").fetchall()
            }
            for column, ddl in columns.items():
                if column not in existing:
                    self._conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {ddl}")

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
            # Bug #18. Persisted with the other counters and, like the edge
            # counters, deliberately NOT folded into `degraded`: a JVM that
            # could not determine its own install mask still produced good
            # evidence of LIFE in this window.
            "stripMaskMissing": health.strip_mask_missing,
            # CONTRACTS 2's canonical C50 booleans, persisted verbatim as the
            # AGENT sent them (`livenessEvidence` stays tri-state: null means
            # a pre-C50 producer said nothing). `windows.liveness_evidence`
            # below is the COLLECTOR's conclusion; keeping both means a later
            # reader can tell "the agent disclaimed it" from "we could not
            # allowlist the label".
            "livenessEvidence": health.liveness_evidence,
            "testRunnerDetected": health.test_runner_detected,
            # CONTRACTS 2 v3. Persisted next to the rest of agentHealth, and
            # deliberately NOT folded into `degraded`.
            **health.edges.to_json(),
        }
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO windows (build_sha, artifact, instance_id, window_start_ms,"
                " window_end_ms, degraded, environment, production, test_tainted,"
                " health_json, received_ms, liveness_evidence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
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
                    int(w.liveness_evidence),
                ),
            )
            window_id = int(cur.lastrowid or 0)
            loaded_ms = w.window_end_ms
            # BUG #22b. `class_loaded` is EVIDENCE, not telemetry: the C10
            # clause reads it, and a class it marks loaded has the
            # `class-never-loaded` blocker REMOVED -- which makes a
            # DEAD_CANDIDATE more likely, not less. So a non-production JVM
            # must not be able to write here, or a laptop that merely loaded a
            # class could help manufacture a candidate for it. This is the
            # only writer of the table, which is why the gate lives here.
            for cls in w.classes_loaded if w.usable_as_life_evidence else ():
                self._conn.execute(
                    "INSERT INTO class_loaded (build_sha, cls, first_loaded_ms,"
                    " last_loaded_ms, load_windows) VALUES (?,?,?,?,1)"
                    " ON CONFLICT(build_sha, cls) DO UPDATE SET"
                    "   first_loaded_ms=MIN(first_loaded_ms, excluded.first_loaded_ms),"
                    "   last_loaded_ms=MAX(last_loaded_ms, excluded.last_loaded_ms),"
                    "   load_windows=load_windows+1",
                    (w.build_sha, cls, loaded_ms, loaded_ms),
                )
            # tier-2 rows ARE stored for a non-production window. They are
            # descriptive telemetry -- latency buckets and exception classes --
            # and no clause in `rules.py` reads them, so they cannot become
            # evidence for or against a verdict. This is what a first-time
            # user actually sees flowing on an unclassified JVM.
            for rec in w.tier2:
                self._conn.execute(
                    "INSERT OR REPLACE INTO tier2 (window_id, build_sha, cls, idx,"
                    " window_end_ms, calls, errors, error_types, buckets, bucket_scheme,"
                    " error_types_source, unattributed_errors, unresolved_id_errors)"
                    " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
                        rec.error_types_source,
                        rec.unattributed_errors,
                        rec.unresolved_id_errors,
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

    # -- ProbeInstallStore (bug #18, optional port extension) -------------

    def merge_probes_installed(
        self, build_sha: str, cls: str, schema_hash: str, installed: bytes
    ) -> None:
        """OR the reported install mask into the stored one.

        Same operation as `merge_coverage` above, on purpose: the mask is the
        union of what every pod installed, because the question it answers is
        "could SOME JVM have observed this index?" What differs is only what a
        zero MEANS -- in `coverage` a zero is an observation, here it is the
        absence of anything that could ever produce one.
        """
        now_ms = self._now_ms()
        with self._lock:
            row = self._conn.execute(
                "SELECT schema_hash, installed FROM class_probes_installed"
                " WHERE build_sha=? AND cls=?",
                (build_sha, cls),
            ).fetchone()
            if row is None:
                existing = b""
            else:
                stored_hash = str(row["schema_hash"])
                if stored_hash != schema_hash:
                    # Loud and no write, exactly as for coverage: the mask is
                    # keyed by build-time probe index, so merging across a
                    # schema change would point the gate at other methods.
                    raise SchemaMismatch(build_sha, cls, stored_hash, schema_hash)
                existing = bytes(row["installed"])
            merged = or_merge(existing, installed)
            self._conn.execute(
                "INSERT INTO class_probes_installed (build_sha, cls, schema_hash, installed,"
                " windows, first_ms, last_ms) VALUES (?,?,?,?,1,?,?)"
                " ON CONFLICT(build_sha, cls) DO UPDATE SET"
                "   installed=excluded.installed,"
                "   windows=windows+1,"
                "   first_ms=MIN(first_ms, excluded.first_ms),"
                "   last_ms=MAX(last_ms, excluded.last_ms)",
                (build_sha, cls, schema_hash, merged, now_ms, now_ms),
            )
            self._conn.commit()

    def probes_installed(self, build_sha: str, cls: str) -> bytes | None:
        """The merged mask, or None when NO window ever reported one."""
        with self._lock:
            row = self._conn.execute(
                "SELECT installed FROM class_probes_installed WHERE build_sha=? AND cls=?",
                (build_sha, cls),
            ).fetchone()
        # `is None` on the ROW, not truthiness on the blob: an all-zero mask
        # is a report, and b"" is falsy.
        return bytes(row["installed"]) if row is not None else None

    def installed_masks(self, build_sha: str) -> dict[str, bytes]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT cls, installed FROM class_probes_installed WHERE build_sha=?"
                " ORDER BY cls",
                (build_sha,),
            ).fetchall()
        return {str(r["cls"]): bytes(r["installed"]) for r in rows}

    def install_mask_windows(self, build_sha: str, cls: str) -> int:
        """How many windows contributed to a class's mask. Adapter extra."""
        with self._lock:
            row = self._conn.execute(
                "SELECT windows FROM class_probes_installed WHERE build_sha=? AND cls=?",
                (build_sha, cls),
            ).fetchone()
        return int(row["windows"]) if row is not None else 0

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

    # -- EdgeStore (SCOPE-v3, optional port extension) -------------------

    def record_edges(self, w: IngestWindow) -> int:
        """Persist `w.edges` and ADD them into the per-build aggregate.

        Deliberately additive. `merge_coverage` three methods up is an
        idempotent OR because the agent sends an accumulated bitset; this is a
        sum because the agent sends a per-window observation count. Replaying
        a window therefore double-counts edges and does not change coverage --
        that asymmetry is in the contract, not a bug here.
        """
        if not w.edges:
            # Still record the sample rate: a window that reported an EMPTY
            # graph is evidence about the tier's configuration, and "absent
            # key" vs "empty graph" are different facts (CONTRACTS 2).
            if w.edges_present:
                with self._lock:
                    self._bump_sample_rate(w.build_sha, w.agent_health.edges.sample_rate)
                    self._conn.commit()
            return 0

        rate = int(w.agent_health.edges.sample_rate or 0)
        when_ms = w.window_end_ms
        with self._lock:
            window_id = self._latest_window_id(w)
            for rec in w.edges:
                self._conn.execute(
                    "INSERT OR REPLACE INTO window_edges (window_id, build_sha, caller_cls,"
                    " caller_idx, callee_cls, callee_idx, observations, sample_rate,"
                    " window_end_ms) VALUES (?,?,?,?,?,?,?,?,?)",
                    (
                        window_id, w.build_sha, rec.caller_cls, rec.caller_idx,
                        rec.callee_cls, rec.callee_idx, rec.count, rate, when_ms,
                    ),
                )
                self._conn.execute(
                    "INSERT INTO edge_agg (build_sha, caller_cls, caller_idx, callee_cls,"
                    " callee_idx, observations, windows, first_seen_ms, last_seen_ms,"
                    " sample_rate_min, sample_rate_max) VALUES (?,?,?,?,?,?,1,?,?,?,?)"
                    " ON CONFLICT(build_sha, caller_cls, caller_idx, callee_cls, callee_idx)"
                    " DO UPDATE SET"
                    "   observations=observations+excluded.observations,"
                    "   windows=windows+1,"
                    "   first_seen_ms=MIN(first_seen_ms, excluded.first_seen_ms),"
                    "   last_seen_ms=MAX(last_seen_ms, excluded.last_seen_ms),"
                    "   sample_rate_min=MIN(sample_rate_min, excluded.sample_rate_min),"
                    "   sample_rate_max=MAX(sample_rate_max, excluded.sample_rate_max)",
                    (
                        w.build_sha, rec.caller_cls, rec.caller_idx, rec.callee_cls,
                        rec.callee_idx, rec.count, when_ms, when_ms, rate, rate,
                    ),
                )
            self._bump_sample_rate(w.build_sha, rate)
            self._conn.commit()
        return len(w.edges)

    def callers_of(self, build_sha: str, cls: str, idx: int) -> list[EdgeAggregate]:
        return self._edges_where(
            "build_sha=? AND callee_cls=? AND callee_idx=?", (build_sha, cls, idx)
        )

    def callees_of(self, build_sha: str, cls: str, idx: int) -> list[EdgeAggregate]:
        return self._edges_where(
            "build_sha=? AND caller_cls=? AND caller_idx=?", (build_sha, cls, idx)
        )

    def hot_edges(self, build_sha: str, limit: int = 50) -> list[EdgeAggregate]:
        return self._edges_where("build_sha=?", (build_sha,), limit=limit)

    def edge_endpoints(self, build_sha: str) -> tuple[set[EdgeEndpoint], set[EdgeEndpoint]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT caller_cls, caller_idx, callee_cls, callee_idx FROM edge_agg"
                " WHERE build_sha=?",
                (build_sha,),
            ).fetchall()
        outbound = {(str(r["caller_cls"]), int(r["caller_idx"])) for r in rows}
        inbound = {(str(r["callee_cls"]), int(r["callee_idx"])) for r in rows}
        return inbound, outbound

    def edge_sample_rates(self, build_sha: str) -> dict[int, int]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT sample_rate, windows FROM edge_sample_rates WHERE build_sha=?"
                " ORDER BY sample_rate",
                (build_sha,),
            ).fetchall()
        return {int(r["sample_rate"]): int(r["windows"]) for r in rows}

    def window_edges(self, build_sha: str) -> list[dict[str, object]]:
        """Per-window edge rows. Adapter extra, for auditing a summed count
        back to the windows and sample rates it came from."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM window_edges WHERE build_sha=?"
                " ORDER BY window_end_ms, window_id",
                (build_sha,),
            ).fetchall()
        return [
            {
                "windowId": int(r["window_id"]),
                "caller": [str(r["caller_cls"]), int(r["caller_idx"])],
                "callee": [str(r["callee_cls"]), int(r["callee_idx"])],
                "sampledObservations": int(r["observations"]),
                "edgesSampleRate": int(r["sample_rate"]) or None,
            }
            for r in rows
        ]

    def _edges_where(
        self, where: str, args: tuple[object, ...], *, limit: int | None = None
    ) -> list[EdgeAggregate]:
        sql = f"SELECT * FROM edge_agg WHERE {where} ORDER BY observations DESC," \
              " callee_cls, callee_idx, caller_cls, caller_idx"
        if limit is not None:
            sql += " LIMIT ?"
            args = (*args, limit)
        with self._lock:
            rows = self._conn.execute(sql, args).fetchall()
        return [
            EdgeAggregate(
                build_sha=str(r["build_sha"]),
                caller_cls=str(r["caller_cls"]),
                caller_idx=int(r["caller_idx"]),
                callee_cls=str(r["callee_cls"]),
                callee_idx=int(r["callee_idx"]),
                sampled_observations=int(r["observations"]),
                windows=int(r["windows"]),
                first_seen=from_epoch_ms(int(r["first_seen_ms"])),
                last_seen=from_epoch_ms(int(r["last_seen_ms"])),
                sample_rate_min=int(r["sample_rate_min"]),
                sample_rate_max=int(r["sample_rate_max"]),
            )
            for r in rows
        ]

    def _bump_sample_rate(self, build_sha: str, rate: int) -> None:
        self._conn.execute(
            "INSERT INTO edge_sample_rates (build_sha, sample_rate, windows)"
            " VALUES (?,?,1) ON CONFLICT(build_sha, sample_rate)"
            " DO UPDATE SET windows=windows+1",
            (build_sha, int(rate or 0)),
        )

    def _latest_window_id(self, w: IngestWindow) -> int:
        row = self._conn.execute(
            "SELECT MAX(window_id) AS id FROM windows WHERE build_sha=? AND instance_id=?"
            " AND window_start_ms=? AND window_end_ms=?",
            (w.build_sha, w.instance_id, w.window_start_ms, w.window_end_ms),
        ).fetchone()
        # 0 = "no window row" -- edges recorded without their window. The
        # aggregate is still correct; only the per-window audit trail is thin.
        return int(row["id"] or 0) if row else 0

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
        """Aggregated tier-2 counters per (class, idx) since `since`.

        Bug #24: carries the exception-class breakdown with the counters
        rather than beside them. `errors` on its own is the number the README
        promised types for and never delivered; handing a caller the count
        without the names is how that happened.
        """
        since_ms = epoch_ms(since)
        with self._lock:
            rows = self._conn.execute(
                "SELECT cls, idx, SUM(calls) AS calls, SUM(errors) AS errors"
                " FROM tier2 WHERE build_sha=? AND window_end_ms>=?"
                " GROUP BY cls, idx ORDER BY SUM(calls) DESC",
                (build_sha, since_ms),
            ).fetchall()
        out: list[dict[str, object]] = []
        for r in rows:
            cls, idx = str(r["cls"]), int(r["idx"])
            out.append(
                {
                    "class": cls,
                    "idx": idx,
                    "calls": int(r["calls"] or 0),
                    "errors": int(r["errors"] or 0),
                    "errorClasses": self.tier2_error_classes(build_sha, cls, idx, since),
                }
            )
        return out

    # -- Tier2ErrorStore (bug #24, optional port extension) ---------------

    def tier2_error_classes(
        self, build_sha: str, cls: str, idx: int, since: datetime
    ) -> dict[str, object]:
        since_ms = epoch_ms(since)
        with self._lock:
            rows = self._conn.execute(
                "SELECT errors, error_types, error_types_source, unattributed_errors,"
                " unresolved_id_errors FROM tier2"
                " WHERE build_sha=? AND cls=? AND idx=? AND window_end_ms>=?",
                (build_sha, cls, idx, since_ms),
            ).fetchall()
        return _fold_error_classes(rows)

    def error_class_totals(
        self, build_sha: str, since: datetime, limit: int = 20
    ) -> dict[str, object]:
        since_ms = epoch_ms(since)
        with self._lock:
            rows = self._conn.execute(
                "SELECT errors, error_types, error_types_source, unattributed_errors,"
                " unresolved_id_errors FROM tier2"
                " WHERE build_sha=? AND window_end_ms>=?",
                (build_sha, since_ms),
            ).fetchall()
        folded = _fold_error_classes(rows)
        ranked = sorted(
            ((name, count) for name, count in folded["byClass"].items()),  # type: ignore[union-attr]
            key=lambda pair: (-pair[1], pair[0]),
        )
        folded["topClasses"] = [
            {"class": name, "errors": count} for name, count in ranked[:limit]
        ]
        folded["distinctClasses"] = len(ranked)
        folded["classesListed"] = min(limit, len(ranked))
        return folded

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
        raw_liveness = health_raw.get("livenessEvidence")
        health = AgentHealth(
            transform_failures=int(health_raw.get("transformFailures", 0)),  # type: ignore[arg-type]
            classes_skipped={str(k): int(v) for k, v in dict(skipped).items()},
            ring_dropped=int(health_raw.get("ringDropped", 0)),  # type: ignore[arg-type]
            clock_ns=int(health_raw.get("clockNs", 0)),  # type: ignore[arg-type]
            clock_degraded=bool(health_raw.get("clockDegraded", False)),
            degraded=bool(health_raw.get("degraded", False)),
            raw=health_raw,
            edges=_edge_health(health_raw),
            strip_mask_missing=_int(health_raw, "stripMaskMissing"),
            # Tri-state preserved on the way back out: a window written before
            # the C50 booleans were read reads back as `None` ("the agent said
            # nothing"), never as `False` ("the agent disclaimed it").
            liveness_evidence=(None if raw_liveness is None else bool(raw_liveness)),
            test_runner_detected=bool(health_raw.get("testRunnerDetected", False)),
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
            liveness_evidence=bool(row["liveness_evidence"]),
        )
