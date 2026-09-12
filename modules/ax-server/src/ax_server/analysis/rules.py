"""The PLAN-v2 correctness rule.

    dead  = runtime-unobserved
          AND static-unreachable            (corroborating only; never a cascade)
          AND NOT suppressed                (repo-checked-in suppression file)
          AND NOT public-API-surface        (downstream consumers are invisible to us)
          AND window covers required phases (month/quarter/year-end, peak, DR drill)
          AND class was observed LOADED     ("never loaded" != "never invoked")
    otherwise -> unknown

Plus the post-freeze additions: NOT_DYNAMICALLY_OBSERVABLE (C51),
short-circuitable methods, the C50 evidence gate, the C55 rule-class kill
switch and the C54 rate cap.

Two properties this module must never lose:

* **UNKNOWN is the default.** Status is computed by starting at UNKNOWN and
  requiring every single clause to pass before it becomes DEAD_CANDIDATE.
  There is no branch that arrives at DEAD_CANDIDATE by elimination.
* **Every rule that fires is recorded, in order.** `reasons` is an audit trail:
  a human must be able to read it top to bottom and reconstruct the verdict
  without rerunning anything. So no clause short-circuits the evaluation --
  only the final status decision consults the collected blockers.

This module NEVER deletes anything. It produces verdicts.
"""

from dataclasses import dataclass, field
from datetime import datetime

from ax_server.analysis.manifest import ClassEntry, MethodEntry
from ax_server.analysis.models import (
    DEAD_CANDIDATE,
    LIVE,
    NOT_DYNAMICALLY_OBSERVABLE,
    UNKNOWN,
    EligibilityClass,
    MethodRef,
    Status,
)
from ax_server.analysis.phases import PhaseCoverage
from ax_server.analysis.suppression import SuppressionRule

__all__ = [
    "BLOCKING_REASON_HEADS",
    "MethodFacts",
    "RuleOutcome",
    "evaluate",
    "first_blocking_reason",
]

#: Reason heads that mean "this clause stopped a nomination". Kept next to
#: the rules themselves so a new blocker cannot be added without landing here.
BLOCKING_REASON_HEADS: frozenset[str] = frozenset(
    {
        "suppressed",
        "clinit",
        "public-api-surface",
        "not-dynamically-observable",
        "de-instrumented",
        "short-circuitable",
        "class-never-loaded",
        "static-reachable",
        "static-unresolved",
        "no-usable-window",
        "phases-missing",
        "window-too-short",
        "rule-class-disabled",
        "rate-limited",
        "runtime-observed",
    }
)


def first_blocking_reason(reasons: list[str]) -> str | None:
    """The first recorded reason that actually prevented a nomination."""
    for reason in reasons:
        if reason.split(":")[0] in BLOCKING_REASON_HEADS:
            return reason
    return None


@dataclass(frozen=True, slots=True)
class MethodFacts:
    """Everything the rule needs, already gathered. No I/O happens in here."""

    ref: MethodRef
    method: MethodEntry
    klass: ClassEntry
    observed: bool
    first_seen: datetime | None
    last_seen: datetime | None
    class_loaded: bool
    static_reachable: bool | None
    suppression: SuppressionRule | None
    phase_coverage: PhaseCoverage
    usable_windows: int
    excluded_windows: int
    entry_point_kind: str = "none"
    efp_disabled_key: str | None = None
    rate_limit_reason: str | None = None
    min_window_days: int = 0


@dataclass(frozen=True, slots=True)
class RuleOutcome:
    status: Status
    reasons: list[str] = field(default_factory=list)
    blockers: tuple[str, ...] = ()


def evaluate(f: MethodFacts) -> RuleOutcome:
    reasons: list[str] = []
    blockers: list[str] = []

    # 1. SUPPRESSION -- CONTRACTS 5, matched before any verdict is computed.
    if f.suppression is not None:
        rule = f.suppression
        reasons.append(
            f"suppressed: matched {rule.pattern!r} at {rule.source}:{rule.line_no}"
            + (f" ({rule.comment})" if rule.comment else "")
        )
        blockers.append("suppressed")
    else:
        reasons.append("not-suppressed: no rule in the suppression file matched")

    # 2. <clinit> -- C44. Never nominate one; treat it as reachable.
    if f.ref.name == "<clinit>":
        reasons.append(
            "clinit: static initialisers are always treated as reachable and are "
            "never nominated (C44; the single most-missed method kind in static graphs)"
        )
        blockers.append("clinit")

    # 3. PUBLIC API SURFACE -- C47. Our runtime says nothing about someone
    #    else's classpath. Measured: 18.5% of downstream clients broken while
    #    the library's own tests passed 98.4%.
    public_api = bool(f.klass.is_public_api)
    if public_api:
        reasons.append(
            "public-api-surface: class is on a published API surface; downstream "
            "consumers are invisible to our runtime data (C47)"
        )
        blockers.append("public-api")
    else:
        reasons.append("not-public-api: class is not marked isPublicApi")

    # 4. DYNAMIC OBSERVABILITY -- C51.
    eligibility = f.method.eligibility
    if eligibility is EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE:
        reasons.append(
            "not-dynamically-observable: constant-returning, compile-time-folded or "
            "single-instruction methods have no distinct frame to observe, so "
            "'unobserved' carries no information (C51)"
        )
        blockers.append("not-dynamically-observable")
    elif eligibility is EligibilityClass.DE_INSTRUMENTED:
        reasons.append(
            "de-instrumented: tier-1b stripped this method's probes, so the absence "
            "of new observations is expected and means nothing (C4)"
        )
        blockers.append("de-instrumented")
    else:
        reasons.append("dynamically-observable: manifest declares a real probe frame")

    # 5. SHORT-CIRCUITABLE -- a Spring proxy, a warm @Cacheable, a
    #    @CircuitBreaker fallback, a @Retryable/@Recover path or an
    #    early-returning filter can serve the feature without the target body
    #    ever running. "Never invoked" then says nothing about the feature.
    if f.method.short_circuitable:
        reasons.append(
            "short-circuitable: a proxy, cache, circuit breaker, retry path or "
            "interceptor can complete the call without entering this body, so a "
            "zero probe is not evidence of disuse"
        )
        blockers.append("short-circuitable")
    else:
        reasons.append("not-short-circuitable: manifest declares the body always runs")

    # 6. RUNTIME OBSERVATION.
    if f.observed:
        reasons.append(
            "runtime-observed: probe set"
            + (f", last seen {f.last_seen.isoformat()}" if f.last_seen else "")
        )
    else:
        reasons.append("runtime-unobserved: probe never set in any accepted window")

    # 7. CLASS LOADED -- C10. "never loaded" != "loaded but never invoked".
    if f.class_loaded:
        reasons.append("class-loaded: the class appeared in classesLoaded")
    else:
        reasons.append(
            "class-never-loaded: no accepted window reported this class as loaded, so "
            "we have no evidence about its methods at all (C10)"
        )
        blockers.append("class-never-loaded")

    # 8. STATIC REACHABILITY -- corroborating only. None is NEVER False.
    if f.static_reachable is True:
        reasons.append("static-reachable: the call graph reaches this method from an entry point")
        blockers.append("static-reachable")
    elif f.static_reachable is False:
        reasons.append("static-unreachable: no resolved caller found by the whole-program scan")
    else:
        reasons.append(
            "static-unresolved: the call graph could not resolve this method; unresolved "
            "means unknown, not dead (A5 measured 61% of executed methods missing)"
        )
        blockers.append("static-unresolved")

    # 9. EVIDENCE QUALITY -- CONTRACTS 2 + C50.
    if f.usable_windows == 0:
        reasons.append(
            "no-usable-window: every window was degraded, non-production or "
            "test-tainted; none may be used as evidence of death"
        )
        blockers.append("no-usable-window")
    else:
        reasons.append(f"usable-windows: {f.usable_windows} accepted as death evidence")
    if f.excluded_windows:
        reasons.append(
            f"windows-excluded: {f.excluded_windows} degraded/non-production/test-tainted "
            "window(s) ignored for the death argument (CONTRACTS 2, C50)"
        )

    # 10. PHASE COVERAGE -- C45.
    pc = f.phase_coverage
    if pc.missing:
        reasons.append(
            "phases-missing: " + ", ".join(pc.missing)
            + " -- a window that missed these is not evidence about the code that "
            "only runs in them (C45)"
        )
        blockers.append("phases-missing")
    else:
        reasons.append("phases-complete: " + ", ".join(pc.covered or ("(none required)",)))
    if f.min_window_days and pc.window_days < f.min_window_days:
        reasons.append(
            f"window-too-short: {pc.window_days}d observed, {f.min_window_days}d required"
        )
        blockers.append("window-too-short")

    # 11. RULE-CLASS KILL SWITCH -- C55.
    if f.efp_disabled_key:
        reasons.append(
            f"rule-class-disabled: {f.efp_disabled_key} crossed the 25% effective "
            "false-positive threshold and self-disabled (C55)"
        )
        blockers.append("rule-class-disabled")

    # 12. RATE CAP -- C54 (safety, not cost).
    if f.rate_limit_reason:
        reasons.append(f"rate-limited: {f.rate_limit_reason}")
        blockers.append("rate-limited")

    # ---- decision ----------------------------------------------------
    # Positive evidence of life beats everything. A degraded agent drops
    # observations, it never invents them, so a set probe is trustworthy.
    if f.observed:
        return RuleOutcome(LIVE, reasons, tuple(blockers))

    if f.suppression is not None:
        # CONTRACTS 5 is explicit: "A suppressed method is UNKNOWN".
        return RuleOutcome(UNKNOWN, reasons, tuple(blockers))

    if f.method.eligibility is EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE:
        return RuleOutcome(NOT_DYNAMICALLY_OBSERVABLE, reasons, tuple(blockers))

    if blockers:
        return RuleOutcome(UNKNOWN, reasons, tuple(blockers))

    reasons.append(
        "DEAD_CANDIDATE: every clause of the PLAN-v2 rule passed. This is a "
        "proposal for a human to review, re-derived on every run and revocable; "
        "nothing is ever auto-deleted."
    )
    return RuleOutcome(DEAD_CANDIDATE, reasons, ())
