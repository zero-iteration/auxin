"""The PLAN-v2 correctness rule, clause by clause.

Every test here is a guard on a measured finding. If one of them starts
failing, the product is manufacturing false dead code.
"""

from datetime import UTC, datetime

from ax_server.analysis.manifest import ClassEntry, MethodEntry
from ax_server.analysis.models import (
    DEAD_CANDIDATE,
    LIVE,
    NOT_DYNAMICALLY_OBSERVABLE,
    UNKNOWN,
    EligibilityClass,
    MethodRef,
)
from ax_server.analysis.phases import PhaseCoverage
from ax_server.analysis.rules import MethodFacts, evaluate
from ax_server.analysis.suppression import Suppressions

COMPLETE = PhaseCoverage(covered=("month-end",), missing=(), window_days=90, span=None)
INCOMPLETE = PhaseCoverage(
    covered=("month-end",), missing=("year-end-close",), window_days=90, span=None
)


def facts(**over):
    """A method that is a DEAD_CANDIDATE unless a clause is flipped."""
    method = MethodEntry(
        idx=0,
        name="legacyFallback",
        desc="()V",
        access="private",
        eligibility=over.pop("eligibility", EligibilityClass.OBSERVABLE),
        short_circuitable=over.pop("short_circuitable", False),
    )
    klass = ClassEntry(
        name="com.acme.A",
        is_public_api=over.pop("public_api", False),
        methods=(method,),
    )
    base = dict(
        ref=MethodRef(klass.name, method.name, method.desc),
        method=method,
        klass=klass,
        observed=False,
        first_seen=None,
        last_seen=None,
        class_loaded=True,
        static_reachable=False,
        suppression=None,
        phase_coverage=COMPLETE,
        usable_windows=4,
        excluded_windows=0,
    )
    base.update(over)
    return MethodFacts(**base)


def test_the_baseline_really_is_a_dead_candidate():
    outcome = evaluate(facts())
    assert outcome.status == DEAD_CANDIDATE
    assert outcome.blockers == ()


def test_unknown_is_the_default_for_every_single_flipped_clause():
    """DEAD_CANDIDATE requires EVERY clause. Flip any one -> UNKNOWN."""
    flips = [
        {"static_reachable": None},
        {"static_reachable": True},
        {"class_loaded": False},
        {"public_api": True},
        {"phase_coverage": INCOMPLETE},
        {"usable_windows": 0},
        {"short_circuitable": True},
        {"suppression": Suppressions.from_text("com.acme.A#*").match(
            MethodRef("com.acme.A", "legacyFallback", "()V"))},
        {"efp_disabled_key": "entrypoint:RequestMapping"},
        {"rate_limit_reason": "cap reached"},
    ]
    for flip in flips:
        assert evaluate(facts(**flip)).status == UNKNOWN, flip


def test_static_reachable_none_never_yields_dead_candidate():
    # A5: static call graphs were measured missing 61% of methods that actually
    # execute. Unresolved means unknown, not dead.
    outcome = evaluate(facts(static_reachable=None))
    assert outcome.status == UNKNOWN
    assert "static-unresolved" in " ".join(outcome.reasons)
    assert "61%" in " ".join(outcome.reasons)


def test_static_reachable_none_is_not_coerced_to_false_anywhere():
    for status in (None, False, True):
        v = evaluate(facts(static_reachable=status))
        assert (v.status == DEAD_CANDIDATE) == (status is False)


def test_clinit_is_never_nominated():
    ref = MethodRef("com.acme.A", "<clinit>", "()V")
    method = MethodEntry(idx=0, name="<clinit>", desc="()V",
                         eligibility=EligibilityClass.OBSERVABLE, short_circuitable=False)
    klass = ClassEntry(name="com.acme.A", methods=(method,))
    outcome = evaluate(facts(ref=ref, method=method, klass=klass))
    assert outcome.status == UNKNOWN
    assert any(r.startswith("clinit:") for r in outcome.reasons)


def test_public_api_is_always_unknown():
    # C47: measured 18.5% of downstream clients broken while the library's own
    # tests passed 98.4%.
    outcome = evaluate(facts(public_api=True))
    assert outcome.status == UNKNOWN
    assert "public-api-surface" in " ".join(outcome.reasons)


def test_public_api_stays_unknown_even_with_everything_else_perfect():
    outcome = evaluate(
        facts(public_api=True, static_reachable=False, class_loaded=True,
              phase_coverage=COMPLETE, usable_windows=10)
    )
    assert outcome.status == UNKNOWN


def test_short_circuitable_is_never_a_dead_candidate():
    """A warm @Cacheable, a proxy, a @CircuitBreaker fallback or a @Retryable
    /@Recover path can serve the feature without the body running."""
    outcome = evaluate(
        facts(short_circuitable=True, observed=False, static_reachable=False,
              suppression=None, public_api=False, phase_coverage=COMPLETE)
    )
    assert outcome.status == UNKNOWN
    assert any(r.startswith("short-circuitable:") for r in outcome.reasons)


def test_missing_shortCircuitable_field_defaults_to_true():
    m = MethodEntry(idx=0, name="x", desc="()V")
    assert m.short_circuitable is True


def test_not_dynamically_observable_has_its_own_status():
    outcome = evaluate(facts(eligibility=EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE))
    assert outcome.status == NOT_DYNAMICALLY_OBSERVABLE
    assert "not-dynamically-observable" in " ".join(outcome.reasons)


def test_de_instrumented_is_distinguishable_and_never_dead():
    outcome = evaluate(facts(eligibility=EligibilityClass.DE_INSTRUMENTED))
    assert outcome.status == UNKNOWN
    assert any(r.startswith("de-instrumented:") for r in outcome.reasons)


def test_suppressed_is_unknown_not_dead():
    rule = Suppressions.from_text("com.acme.A#*  # break-glass").match(
        MethodRef("com.acme.A", "legacyFallback", "()V")
    )
    outcome = evaluate(facts(suppression=rule))
    assert outcome.status == UNKNOWN
    assert "suppressed:" in outcome.reasons[0]
    assert "break-glass" in outcome.reasons[0]


def test_phases_missing_can_never_be_dead():
    outcome = evaluate(facts(phase_coverage=INCOMPLETE))
    assert outcome.status == UNKNOWN
    assert "phases-missing: year-end-close" in " ".join(outcome.reasons)


def test_observation_beats_every_blocker():
    outcome = evaluate(
        facts(observed=True, last_seen=datetime(2026, 9, 1, tzinfo=UTC),
              public_api=True, suppression=None)
    )
    assert outcome.status == LIVE


def test_no_usable_window_can_never_be_dead():
    outcome = evaluate(facts(usable_windows=0))
    assert outcome.status == UNKNOWN
    assert "no-usable-window" in " ".join(outcome.reasons)


def test_reasons_list_every_rule_that_fired_in_order():
    outcome = evaluate(facts())
    heads = [r.split(":")[0] for r in outcome.reasons]
    assert heads == [
        "not-suppressed",
        "not-public-api",
        "dynamically-observable",
        "not-short-circuitable",
        "runtime-unobserved",
        "class-loaded",
        "static-unreachable",
        "usable-windows",
        "phases-complete",
        "DEAD_CANDIDATE",
    ]


def test_reasons_are_auditable_when_several_rules_block():
    outcome = evaluate(facts(public_api=True, static_reachable=None, class_loaded=False))
    assert set(outcome.blockers) >= {"public-api", "static-unresolved", "class-never-loaded"}
    assert len(outcome.reasons) >= len(outcome.blockers)


def test_min_window_days_is_an_extra_conservative_gate():
    outcome = evaluate(facts(min_window_days=180))
    assert outcome.status == UNKNOWN
    assert "window-too-short" in " ".join(outcome.reasons)
