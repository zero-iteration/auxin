"""C53 -- continuous re-validation. A verdict is a standing claim, not a snapshot.

SCARF (Meta), verbatim:

    "the properties of the candidate subgraph that led to safe removal
     initiation must continue to hold true at every run of the system: if the
     subgraph stops being a candidate, then safe removal is aborted."

So DEAD_CANDIDATE is revocable. Every analysis run re-derives every verdict; a
single new observation, a new static edge, a new suppression line or a newly
missing phase silently revokes the proposal. What we persist is the ledger:
when we first proposed it, when we last confirmed it, and -- if it went away --
exactly why.

This ledger is owned by `analysis`. It is deliberately NOT part of the frozen
CONTRACTS 3 store port.
"""

import sqlite3
import threading
from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import UTC, datetime

from ax_server.analysis.models import MethodRef

__all__ = ["Proposal", "ProposalLedger", "SqliteProposalLedger"]

_SCHEMA = """
CREATE TABLE IF NOT EXISTS proposals (
    build_sha        TEXT NOT NULL,
    artifact         TEXT NOT NULL,
    cls              TEXT NOT NULL,
    method           TEXT NOT NULL,
    desc             TEXT NOT NULL,
    first_proposed_ms INTEGER NOT NULL,
    last_confirmed_ms INTEGER NOT NULL,
    active           INTEGER NOT NULL,
    revoked_ms       INTEGER,
    revoked_reason   TEXT,
    PRIMARY KEY (build_sha, cls, method, desc)
);
CREATE INDEX IF NOT EXISTS proposals_active
    ON proposals(artifact, active, first_proposed_ms);
"""


@dataclass(frozen=True, slots=True)
class Proposal:
    build_sha: str
    artifact: str
    ref: MethodRef
    first_proposed_at: datetime
    last_confirmed_at: datetime
    active: bool
    revoked_at: datetime | None = None
    revoked_reason: str | None = None


class ProposalLedger(ABC):
    """Port. The SQLite adapter is the only implementation today."""

    @abstractmethod
    def get(self, build_sha: str, ref: MethodRef) -> Proposal | None: ...

    @abstractmethod
    def confirm(
        self, build_sha: str, artifact: str, ref: MethodRef, when: datetime
    ) -> Proposal:
        """Record or re-confirm a live proposal. `first_proposed_at` is set once."""

    @abstractmethod
    def revoke(self, build_sha: str, ref: MethodRef, when: datetime, reason: str) -> None:
        """Abort a proposal that has stopped being a candidate."""

    @abstractmethod
    def active(self, build_sha: str | None = None) -> list[Proposal]: ...

    @abstractmethod
    def proposed_on(self, artifact: str, day: datetime) -> int:
        """How many NEW proposals were opened for this artifact on `day` (UTC)."""


def _ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def _dt(ms: int | None) -> datetime | None:
    return datetime.fromtimestamp(ms / 1000.0, tz=UTC) if ms is not None else None


class SqliteProposalLedger(ProposalLedger):
    def __init__(self, path: str = ":memory:") -> None:
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(_SCHEMA)
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def get(self, build_sha: str, ref: MethodRef) -> Proposal | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM proposals WHERE build_sha=? AND cls=? AND method=? AND desc=?",
                (build_sha, ref.cls, ref.name, ref.desc),
            ).fetchone()
        return self._row(row) if row else None

    def confirm(self, build_sha: str, artifact: str, ref: MethodRef, when: datetime) -> Proposal:
        with self._lock:
            self._conn.execute(
                "INSERT INTO proposals (build_sha, artifact, cls, method, desc,"
                " first_proposed_ms, last_confirmed_ms, active, revoked_ms, revoked_reason)"
                " VALUES (?,?,?,?,?,?,?,1,NULL,NULL)"
                " ON CONFLICT(build_sha, cls, method, desc) DO UPDATE SET"
                "   last_confirmed_ms=excluded.last_confirmed_ms,"
                "   active=1, revoked_ms=NULL, revoked_reason=NULL",
                (build_sha, artifact, ref.cls, ref.name, ref.desc, _ms(when), _ms(when)),
            )
            self._conn.commit()
        proposal = self.get(build_sha, ref)
        assert proposal is not None
        return proposal

    def revoke(self, build_sha: str, ref: MethodRef, when: datetime, reason: str) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE proposals SET active=0, revoked_ms=?, revoked_reason=?"
                " WHERE build_sha=? AND cls=? AND method=? AND desc=? AND active=1",
                (_ms(when), reason, build_sha, ref.cls, ref.name, ref.desc),
            )
            self._conn.commit()

    def active(self, build_sha: str | None = None) -> list[Proposal]:
        sql = "SELECT * FROM proposals WHERE active=1"
        args: tuple[object, ...] = ()
        if build_sha is not None:
            sql += " AND build_sha=?"
            args = (build_sha,)
        sql += " ORDER BY first_proposed_ms"
        with self._lock:
            rows = self._conn.execute(sql, args).fetchall()
        return [self._row(r) for r in rows]

    def proposed_on(self, artifact: str, day: datetime) -> int:
        start = day.astimezone(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
        end = start.timestamp() * 1000 + 86_400_000
        with self._lock:
            row = self._conn.execute(
                "SELECT COUNT(*) AS n FROM proposals WHERE artifact=?"
                " AND first_proposed_ms>=? AND first_proposed_ms<?",
                (artifact, _ms(start), int(end)),
            ).fetchone()
        return int(row["n"]) if row else 0

    @staticmethod
    def _row(row: sqlite3.Row) -> Proposal:
        return Proposal(
            build_sha=str(row["build_sha"]),
            artifact=str(row["artifact"]),
            ref=MethodRef(str(row["cls"]), str(row["method"]), str(row["desc"])),
            first_proposed_at=_dt(int(row["first_proposed_ms"])),  # type: ignore[arg-type]
            last_confirmed_at=_dt(int(row["last_confirmed_ms"])),  # type: ignore[arg-type]
            active=bool(row["active"]),
            revoked_at=_dt(row["revoked_ms"]),
            revoked_reason=row["revoked_reason"],
        )
