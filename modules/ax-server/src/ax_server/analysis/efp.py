"""C55 -- "effective false positive" as the north-star metric.

Google Tricorder's definition, verbatim:

    "any report from the tool where a user chooses not to take action to
     resolve the report."

Not "the verdict was logically wrong" -- "the human didn't act on it". That is
the only definition that survives contact with a codebase where a technically
correct dead-code report is still not worth a diff.

Tricorder's enforcement, which we copy:
  >= 90% actionable required for a new analyzer
  >= 10% not-useful  -> PROBATION
  >= 25% not-useful  -> the rule class can be turned off immediately
  best analyzers run 0-3%

Tracked per entry-point class and per eligibility class, because those are the
two dimensions along which this analysis is most likely to be systematically
wrong (a `@Scheduled` root behaves nothing like a `RequestMapping` root).

The IDE and MCP feedback surfaces that FEED these counters do not exist yet.
The counters and the self-disable logic exist now anyway, because retrofitting
a metric after launch is how you end up never having it. Nobody in this space
publishes this number.
"""

import sqlite3
import threading
from dataclasses import dataclass
from enum import StrEnum

__all__ = ["EfpStatus", "EfpTracker", "RuleClassStats", "efp_keys"]

PROBATION_THRESHOLD = 0.10
DISABLE_THRESHOLD = 0.25
#: Below this many reports the rate is noise; one grumpy reviewer should not
#: disable a rule class.
DEFAULT_MIN_REPORTS = 10

_SCHEMA = """
CREATE TABLE IF NOT EXISTS efp (
    rule_key     TEXT PRIMARY KEY,
    reports      INTEGER NOT NULL DEFAULT 0,
    actioned     INTEGER NOT NULL DEFAULT 0,
    not_useful   INTEGER NOT NULL DEFAULT 0,
    force_state  TEXT
);
"""


class EfpStatus(StrEnum):
    ACTIVE = "ACTIVE"
    PROBATION = "PROBATION"
    DISABLED = "DISABLED"


@dataclass(frozen=True, slots=True)
class RuleClassStats:
    rule_key: str
    reports: int
    actioned: int
    not_useful: int
    status: EfpStatus

    @property
    def judged(self) -> int:
        """Reports that actually received a human decision."""
        return self.actioned + self.not_useful

    @property
    def rate(self) -> float:
        """Effective-false-positive rate over JUDGED reports.

        Unjudged reports are excluded on purpose: counting "nobody has looked
        yet" as "nobody found it useful" would disable every new rule class on
        day one.
        """
        return self.not_useful / self.judged if self.judged else 0.0

    def to_json(self) -> dict[str, object]:
        return {
            "ruleKey": self.rule_key,
            "reports": self.reports,
            "judged": self.judged,
            "actioned": self.actioned,
            "notUseful": self.not_useful,
            "effectiveFalsePositiveRate": round(self.rate, 4),
            "status": str(self.status),
        }


def efp_keys(entry_point_kind: str, eligibility: str) -> tuple[str, ...]:
    """The rule-class keys a single verdict is accounted against."""
    return (f"entrypoint:{entry_point_kind}", f"eligibility:{eligibility}")


class EfpTracker:
    """Counters plus the self-disable decision."""

    def __init__(
        self,
        path: str = ":memory:",
        *,
        min_reports: int = DEFAULT_MIN_REPORTS,
        probation_threshold: float = PROBATION_THRESHOLD,
        disable_threshold: float = DISABLE_THRESHOLD,
    ) -> None:
        self._lock = threading.RLock()
        self._min_reports = min_reports
        self._probation = probation_threshold
        self._disable = disable_threshold
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(_SCHEMA)
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # -- writes ----------------------------------------------------------

    def record_report(self, *keys: str) -> None:
        """A DEAD_CANDIDATE was shown to a human under these rule classes."""
        self._bump(keys, "reports")

    def record_feedback(self, keys: tuple[str, ...], *, actioned: bool) -> None:
        """The human either acted on the report or chose not to.

        `actioned=False` is an EFFECTIVE false positive even when the verdict
        was technically defensible.
        """
        self._bump(keys, "actioned" if actioned else "not_useful")

    def force(self, rule_key: str, status: EfpStatus | None) -> None:
        """Manual override, e.g. re-enabling a rule class after a fix."""
        with self._lock:
            self._conn.execute(
                "INSERT INTO efp (rule_key, force_state) VALUES (?,?)"
                " ON CONFLICT(rule_key) DO UPDATE SET force_state=excluded.force_state",
                (rule_key, str(status) if status else None),
            )
            self._conn.commit()

    def _bump(self, keys: tuple[str, ...], column: str) -> None:
        with self._lock:
            for key in keys:
                self._conn.execute(
                    f"INSERT INTO efp (rule_key, {column}) VALUES (?,1)"
                    f" ON CONFLICT(rule_key) DO UPDATE SET {column}={column}+1",
                    (key,),
                )
            self._conn.commit()

    # -- reads -----------------------------------------------------------

    def stats(self, rule_key: str) -> RuleClassStats:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM efp WHERE rule_key=?", (rule_key,)
            ).fetchone()
        if row is None:
            return RuleClassStats(rule_key, 0, 0, 0, EfpStatus.ACTIVE)
        return self._stats(row)

    def all_stats(self) -> list[RuleClassStats]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM efp ORDER BY rule_key").fetchall()
        return [self._stats(r) for r in rows]

    def status(self, rule_key: str) -> EfpStatus:
        return self.stats(rule_key).status

    def disabled(self, keys: tuple[str, ...]) -> str | None:
        """Return the first key that is DISABLED, or None."""
        for key in keys:
            if self.status(key) is EfpStatus.DISABLED:
                return key
        return None

    def _stats(self, row: sqlite3.Row) -> RuleClassStats:
        reports = int(row["reports"] or 0)
        actioned = int(row["actioned"] or 0)
        not_useful = int(row["not_useful"] or 0)
        forced = row["force_state"]
        judged = actioned + not_useful
        if forced:
            status = EfpStatus(str(forced))
        elif judged < self._min_reports:
            status = EfpStatus.ACTIVE
        else:
            rate = not_useful / judged
            if rate >= self._disable:
                status = EfpStatus.DISABLED
            elif rate >= self._probation:
                status = EfpStatus.PROBATION
            else:
                status = EfpStatus.ACTIVE
        return RuleClassStats(str(row["rule_key"]), reports, actioned, not_useful, status)
