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

Bug #18 adds the installed-probe gate, and it is the same shape of rule as the
runtime call graph below -- a zero that means NOTHING must not be read as a
zero that means SOMETHING::

    probes                        -> liveness (a set bit means it ran)
    probesInstalled & ~probes     -> the ONLY death evidence
    ~probesInstalled              -> SILENCE. not evidence of anything.

An index the emitter never installed a probe at is never written by anything,
so its bit is permanently zero. `ax.tier1.enabled=false` makes that true of
EVERY index in a JVM while every method stays `dynamicallyObservable: true` --
which is why the manifest cannot express this and the mask has to.

SCOPE-v3 adds the sampled runtime call graph, and it enters the rule in ONE
direction only. There is no `AND runtime-unreachable` clause above and there
never can be: the tier samples 1-in-N root entries, so absence of an edge is
absence of data. An OBSERVED inbound edge, on the other hand, is positive
proof of life and short-circuits straight to LIVE.

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
    effective_eligibility,
)
from ax_server.analysis.phases import PhaseCoverage
from ax_server.analysis.suppression import SuppressionRule

__all__ = [
    "BLOCKING_REASON_HEADS",
    "NON_EVIDENCE_REASON_HEADS",
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
        "no-probe-installed",
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
        "runtime-edge-observed",
    }
)

#: Reason heads the rule emits that are NOT evidence of anything and must
#: never appear in `blockers` -- nor be mistaken for support for a nomination.
#: `runtime-edge-absent` is here because the edge graph is sampled: it is
#: narrated for the audit trail and contributes nothing to the decision.
NON_EVIDENCE_REASON_HEADS: frozenset[str] = frozenset({"runtime-edge-absent"})


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
    # -- installed-probe mask (bug #18) --------------------------------
    # TRI-STATE, and there is deliberately no `probe_missing: bool` here for
    # the same reason there is no `runtime_reachable: bool` below: a boolean
    # invites "no mask reported" to be read as "no probe installed", and that
    # reading deletes every candidate in a pre-#18 build without saying so.
    #
    #   True   a probe exists at this index in some JVM -> an unset bit is a
    #          real observation of non-execution
    #   False  NO JVM ever installed one -> the bit is zero because nothing
    #          can write it, and the clause BLOCKS
    #   None   no mask was reported for this class -> the clause ABSTAINS
    probe_installed: bool | None = None
    efp_disabled_key: str | None = None
    rate_limit_reason: str | None = None
    min_window_days: int = 0
    # -- SCOPE-v3 sampled runtime call graph ---------------------------
    # Distinct from `static_reachable` above and never merged with it. There
    # is no `runtime_reachable: bool` here for a reason: the only two states
    # this tier can produce are "observed" (> 0 edges) and "no information"
    # (0 edges), and a boolean invites the second to be read as the negation
    # of the first.
    #: Distinct OBSERVED inbound edges. 0 means NOTHING IS KNOWN.
    runtime_inbound_edges: int = 0
    runtime_outbound_edges: int = 0
    #: Raw observations in sampled traces behind those inbound edges.
    runtime_inbound_observations: int = 0
    runtime_edges_sample_rate: int | None = None
    #: Did the edge tier report at all for this build? When False the rule
    #: stays silent about edges: a tier that never spoke fired no rule.
    runtime_edges_reported: bool = False

    @property
    def runtime_reachable(self) -> bool | None:
        """True when an inbound edge was observed, else None. NEVER False."""
        return True if self.runtime_inbound_edges > 0 else None


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

    # 4b. PROBE INSTALLATION -- BUG #18. The probe array is sized to the
    #     manifest's probeCount, but the emitter installs a probe at only some
    #     of those indices, and an index with no probe is NEVER WRITTEN BY
    #     ANYTHING. Its bit is permanently zero.
    #
    #     Note there is no `not f.probe_installed` and no `== 0` anywhere in
    #     this function: the three states are read with `is`, so "no mask was
    #     reported" can never collapse into "no probe was installed" (which
    #     would silently erase every candidate in a pre-#18 build) and
    #     "no probe was installed" can never collapse into "nothing is known"
    #     (which is bug #18 itself). An AST test pins that.
    # `effective_eligibility` is the ONE place the manifest's class and the
    # runtime mask are combined, and it is shared with the engine, so the
    # reason text here and the `eligibility` field an operator filters on can
    # never disagree about which of the three cases this is.
    if effective_eligibility(eligibility, f.probe_installed) is (
        EligibilityClass.NO_PROBE_INSTALLED
    ):
        reasons.append(
            "no-probe-installed: the manifest declares this method dynamically "
            f"observable, but NO JVM ever installed a probe at index {f.method.idx} "
            "-- ax.tier1.enabled=false, frame emission unsupported for this "
            "bytecode shape, or a class-file fallback. Its bit is zero because "
            "nothing can write it, not because the method never ran, so this is "
            "silence and not evidence (bug #18)"
        )
        blockers.append("no-probe-installed")
    elif eligibility is EligibilityClass.OBSERVABLE and f.probe_installed is True:
        reasons.append(
            "probe-installed: a probe really exists at this index, so an unset bit "
            "is a real observation of non-execution (bug #18)"
        )
    # `is None` -- no window reported a mask for this class -- narrates
    # nothing, exactly as an edge tier that never spoke fires no rule. A
    # clause that cannot see is not a clause that saw nothing.

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

    # 8b. RUNTIME CALL EDGES -- SCOPE-v3. ONE-DIRECTIONAL BY CONSTRUCTION.
    #
    #     present  => LIVE, full stop. A sampled trace saw a real caller enter
    #                 this method in production. Nothing over-approximates
    #                 here: the edge happened.
    #     absent   => NOTHING. Not a blocker, not support, not a tiebreak.
    #                 The tier samples 1-in-N root entries and is depth-,
    #                 per-root- and distinct-bounded with drop-on-full, so the
    #                 hottest method in the build can legitimately show zero
    #                 edges. CONTRACTS 2 v3: "Its absence is never evidence of
    #                 death... `edges[]` must never feed a DEAD_CANDIDATE
    #                 verdict."
    #
    # Note there is no `else: blockers.append(...)` below, and no clause
    # anywhere in this function that reads `runtime_inbound_edges == 0`. That
    # absence is the enforcement.
    if f.runtime_inbound_edges > 0:
        rate = f.runtime_edges_sample_rate
        scale = f"1-in-{rate}" if rate else "an undeclared rate"
        reasons.append(
            f"runtime-edge-observed: {f.runtime_inbound_edges} observed inbound edge(s), "
            f"{f.runtime_inbound_observations} sampled observation(s) at {scale} -- a real "
            "caller really entered this method in production, so it is LIVE regardless of "
            "the probe bits (SCOPE-v3)"
        )
        blockers.append("runtime-edge-observed")
    elif f.runtime_edges_reported:
        reasons.append(
            "runtime-edge-absent: no observed inbound edge, which is NOT evidence -- the "
            "edge tier is sampled per root entry and additionally depth-, per-root- and "
            "distinct-bounded with drop-on-full, so absence carries no information and is "
            "excluded from this verdict entirely (CONTRACTS 2 v3)"
        )
        # Intentionally NOT a blocker and intentionally NOT support.

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

    # An OBSERVED inbound edge is the same kind of fact as a set probe: it is
    # positive, it cannot be produced by a lossy tier inventing data, and it
    # comes from the same C50-gated window. So presence => LIVE, full stop --
    # even where the probe bit is missing, which happens legitimately when
    # tier-1b has stripped the callee's probe (C4) while the edge tier keeps
    # running, the two tiers being independent by design.
    if f.runtime_inbound_edges > 0:
        return RuleOutcome(LIVE, reasons, tuple(blockers))

    if f.suppression is not None:
        # CONTRACTS 5 is explicit: "A suppressed method is UNKNOWN".
        return RuleOutcome(UNKNOWN, reasons, tuple(blockers))

    if f.method.eligibility is EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE:
        return RuleOutcome(NOT_DYNAMICALLY_OBSERVABLE, reasons, tuple(blockers))

    # EVERY clause that blocks lands here, and this gate is the structural
    # reason none of them can leak into a nomination -- including
    # `no-probe-installed`, which is the one that used to read as death. There
    # is exactly one DEAD_CANDIDATE return below it and none above it; an AST
    # test pins that, so a future clause cannot be added downstream of the
    # decision by accident.
    if blockers:
        return RuleOutcome(UNKNOWN, reasons, tuple(blockers))

    reasons.append(
        "DEAD_CANDIDATE: every clause of the PLAN-v2 rule passed. This is a "
        "proposal for a human to review, re-derived on every run and revocable; "
        "nothing is ever auto-deleted."
    )
    return RuleOutcome(DEAD_CANDIDATE, reasons, ())
