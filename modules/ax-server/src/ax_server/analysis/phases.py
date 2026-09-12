"""C45 -- phase-aware observation windows.

Teamscale/CQSE, the best published window policy in existence:

    "we record code execution for several months and make sure to target
     important time intervals like the time of the year-end closing."

Duration alone is not evidence. A 90-day window that missed year-end close is
not 90 days of evidence about year-end code, so a non-empty `phases_missing`
can never yield a DEAD_CANDIDATE.

The calendar is configuration, not code: recurring rules cover month/quarter/
year boundaries, and explicit occurrences cover the ones only the business
knows (peak season, DR drill).
"""

import calendar
import json
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

__all__ = [
    "DEFAULT_REQUIRED_PHASES",
    "Interval",
    "PhaseCalendar",
    "PhaseCoverage",
    "PhaseOccurrence",
    "merge_intervals",
    "overlap_seconds",
]

#: PLAN-v2: "window covers required phases (month/quarter/year-end, peak, DR drill)".
DEFAULT_REQUIRED_PHASES: tuple[str, ...] = (
    "month-end",
    "quarter-end",
    "year-end-close",
    "peak-season",
    "dr-drill",
)

Interval = tuple[datetime, datetime]

_RECURRING_RULES = frozenset(
    {"last-days-of-month", "last-days-of-quarter", "last-days-of-year"}
)


@dataclass(frozen=True, slots=True)
class PhaseOccurrence:
    phase: str
    start: datetime
    end: datetime
    label: str = ""

    @property
    def duration_seconds(self) -> float:
        return (self.end - self.start).total_seconds()


@dataclass(frozen=True, slots=True)
class PhaseCoverage:
    """What the observation window actually spanned."""

    covered: tuple[str, ...]
    missing: tuple[str, ...]
    window_days: int
    span: Interval | None
    detail: dict[str, str] = field(default_factory=dict)

    @property
    def complete(self) -> bool:
        return not self.missing


def merge_intervals(intervals: Iterable[Interval]) -> list[Interval]:
    """Union of half-open intervals, sorted and coalesced."""
    ordered = sorted((s, e) for s, e in intervals if e > s)
    out: list[Interval] = []
    for start, end in ordered:
        if out and start <= out[-1][1]:
            if end > out[-1][1]:
                out[-1] = (out[-1][0], end)
        else:
            out.append((start, end))
    return out


def overlap_seconds(union: Sequence[Interval], window: Interval) -> float:
    """Seconds of `window` covered by the merged interval union."""
    total = 0.0
    w_start, w_end = window
    for start, end in union:
        lo = max(start, w_start)
        hi = min(end, w_end)
        if hi > lo:
            total += (hi - lo).total_seconds()
    return total


def _day_start(dt: datetime) -> datetime:
    return dt.replace(hour=0, minute=0, second=0, microsecond=0)


def _month_tail(year: int, month: int, days: int, phase: str) -> PhaseOccurrence:
    last_day = calendar.monthrange(year, month)[1]
    end = datetime(year, month, last_day, tzinfo=UTC) + timedelta(days=1)
    start = end - timedelta(days=max(1, days))
    return PhaseOccurrence(phase, start, end, label=f"{year}-{month:02d}")


@dataclass(frozen=True, slots=True)
class PhaseCalendar:
    """Which business phases must be spanned, and when they happen."""

    required: tuple[str, ...] = DEFAULT_REQUIRED_PHASES
    occurrences: tuple[PhaseOccurrence, ...] = ()
    recurring: tuple[dict[str, Any], ...] = ()
    #: Fraction of a phase occurrence that must be inside the observed union
    #: before the phase counts as spanned. Flushes are per-minute, so a real
    #: deployment is near-continuous; anything below this is a gap.
    min_coverage_fraction: float = 0.9

    # -- construction ----------------------------------------------------

    @classmethod
    def default(cls) -> "PhaseCalendar":
        """Month/quarter/year-end by rule; peak season and DR drill must be
        declared explicitly because only the business knows them."""
        return cls(
            required=DEFAULT_REQUIRED_PHASES,
            recurring=(
                {"phase": "month-end", "rule": "last-days-of-month", "days": 2},
                {"phase": "quarter-end", "rule": "last-days-of-quarter", "days": 3},
                {"phase": "year-end-close", "rule": "last-days-of-year", "days": 5},
            ),
        )

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "PhaseCalendar":
        occurrences = tuple(
            PhaseOccurrence(
                phase=str(o["phase"]),
                start=_parse_dt(o["start"]),
                end=_parse_dt(o["end"]),
                label=str(o.get("label", "")),
            )
            for o in raw.get("occurrences", ())
        )
        recurring = tuple(dict(r) for r in raw.get("recurring", ()))
        for rule in recurring:
            if rule.get("rule") not in _RECURRING_RULES:
                raise ValueError(
                    f"unknown recurring rule {rule.get('rule')!r}; "
                    f"expected one of {sorted(_RECURRING_RULES)}"
                )
        return cls(
            required=tuple(raw.get("requiredPhases", DEFAULT_REQUIRED_PHASES)),
            occurrences=occurrences,
            recurring=recurring,
            min_coverage_fraction=float(raw.get("minCoverageFraction", 0.9)),
        )

    @classmethod
    def from_file(cls, path: str | Path) -> "PhaseCalendar":
        p = Path(path)
        if not p.exists():
            return cls.default()
        return cls.from_dict(json.loads(p.read_text(encoding="utf-8")))

    # -- evaluation ------------------------------------------------------

    def expand(self, span: Interval) -> list[PhaseOccurrence]:
        """Every occurrence overlapping `span`, rules expanded."""
        start, end = span
        out = [o for o in self.occurrences if o.end > start and o.start < end]
        for rule in self.recurring:
            out.extend(self._expand_rule(rule, start, end))
        return sorted(out, key=lambda o: (o.phase, o.start))

    @staticmethod
    def _expand_rule(rule: dict[str, Any], start: datetime, end: datetime) -> list[PhaseOccurrence]:
        phase = str(rule["phase"])
        kind = str(rule["rule"])
        days = int(rule.get("days", 1))
        out: list[PhaseOccurrence] = []
        year, month = start.year, start.month
        # Walk one month before and after so a tail straddling the boundary is
        # not lost.
        cursor = datetime(year, month, 1, tzinfo=UTC) - timedelta(days=1)
        limit = end + timedelta(days=40)
        while cursor <= limit:
            y, m = cursor.year, cursor.month
            include = (
                kind == "last-days-of-month"
                or (kind == "last-days-of-quarter" and m in (3, 6, 9, 12))
                or (kind == "last-days-of-year" and m == 12)
            )
            if include:
                occ = _month_tail(y, m, days, phase)
                if occ.end > start and occ.start < end:
                    out.append(occ)
            cursor = datetime(y + (m == 12), (m % 12) + 1, 1, tzinfo=UTC)
        return out

    def evaluate(self, intervals: Sequence[Interval]) -> PhaseCoverage:
        """Which required phases the observed intervals actually spanned."""
        union = merge_intervals(intervals)
        if not union:
            return PhaseCoverage(
                covered=(),
                missing=tuple(self.required),
                window_days=0,
                span=None,
                detail={p: "no usable observation window at all" for p in self.required},
            )
        span = (union[0][0], union[-1][1])
        window_days = int((span[1] - span[0]).total_seconds() // 86400)
        occurrences = self.expand(span)

        covered: list[str] = []
        missing: list[str] = []
        detail: dict[str, str] = {}
        for phase in self.required:
            candidates = [o for o in occurrences if o.phase == phase]
            if not candidates:
                missing.append(phase)
                detail[phase] = (
                    "no occurrence of this phase falls inside the observation span "
                    f"{span[0].date()}..{span[1].date()}"
                )
                continue
            best = 0.0
            best_label = ""
            for occ in candidates:
                if occ.duration_seconds <= 0:
                    continue
                fraction = overlap_seconds(union, (occ.start, occ.end)) / occ.duration_seconds
                if fraction > best:
                    best, best_label = fraction, occ.label or str(occ.start.date())
            if best >= self.min_coverage_fraction:
                covered.append(phase)
                detail[phase] = f"covered {best:.0%} of occurrence {best_label}"
            else:
                missing.append(phase)
                detail[phase] = (
                    f"best occurrence only {best:.0%} observed "
                    f"(need {self.min_coverage_fraction:.0%})"
                )
        return PhaseCoverage(
            covered=tuple(covered),
            missing=tuple(missing),
            window_days=window_days,
            span=span,
            detail=detail,
        )


def _parse_dt(value: Any) -> datetime:
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    dt = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return dt if dt.tzinfo else dt.replace(tzinfo=UTC)
