"""C54 -- rate limiting as a SAFETY control, not a cost control.

SCARF onboards a new system at **5 assets/day**, and states the reason plainly:

    "even if SCARF incorrectly marks assets as deprecated, it will do so at a
     limited speed and thus give more time to detect and remediate."

That is the entire rationale, and it is worth restating because it inverts the
usual engineering instinct. This limiter exists to make a WRONG verdict
survivable, not to save CPU. Emitting 400 correct proposals in an hour and
emitting 400 wrong ones look identical from inside the system; emitting them
slowly is what buys a human the chance to notice the difference. A single SCARF
deprecation takes over a month end to end.

Only NEW proposals are limited. Re-confirming a standing claim (C53) is free --
throttling re-validation would create the opposite hazard, where a stale
proposal survives because we were too busy to re-check it.
"""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime

from ax_server.analysis.proposals import ProposalLedger

__all__ = ["DEFAULT_PROPOSALS_PER_DAY", "RateLimitDecision", "ProposalRateLimiter"]

#: SCARF's published onboarding rate. Deliberately low.
DEFAULT_PROPOSALS_PER_DAY = 5


@dataclass(frozen=True, slots=True)
class RateLimitDecision:
    allowed: bool
    used: int
    cap: int
    reason: str


class ProposalRateLimiter:
    """Caps NEW DEAD_CANDIDATE proposals per day per service (artifact)."""

    def __init__(
        self,
        ledger: ProposalLedger,
        *,
        per_day: int = DEFAULT_PROPOSALS_PER_DAY,
        clock: Callable[[], datetime] = lambda: datetime.now(tz=UTC),
    ) -> None:
        if per_day < 0:
            raise ValueError("per_day must be >= 0")
        self._ledger = ledger
        self._per_day = per_day
        self._clock = clock
        self._granted_this_run: dict[tuple[str, str], int] = {}

    @property
    def per_day(self) -> int:
        return self._per_day

    def begin_run(self) -> None:
        """Reset the in-run tally. One analysis run = one budget consumption."""
        self._granted_this_run.clear()

    def check(self, artifact: str, *, when: datetime | None = None) -> RateLimitDecision:
        now = when or self._clock()
        day_key = (artifact, now.astimezone(UTC).date().isoformat())
        already = self._ledger.proposed_on(artifact, now) + self._granted_this_run.get(day_key, 0)
        if already >= self._per_day:
            return RateLimitDecision(
                False,
                already,
                self._per_day,
                f"daily new-proposal cap reached for {artifact} "
                f"({already}/{self._per_day}); deferred to the next run (C54 safety cap)",
            )
        return RateLimitDecision(True, already, self._per_day, "within daily cap")

    def consume(self, artifact: str, *, when: datetime | None = None) -> None:
        now = when or self._clock()
        day_key = (artifact, now.astimezone(UTC).date().isoformat())
        self._granted_this_run[day_key] = self._granted_this_run.get(day_key, 0) + 1
