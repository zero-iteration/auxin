"""C54 rate limiting (safety) and C55 effective false positives (self-disable)."""

from datetime import UTC, datetime, timedelta

from ax_server.analysis.efp import EfpStatus, EfpTracker, efp_keys
from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.models import DEAD_CANDIDATE, UNKNOWN
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.analysis.ratelimit import (
    DEFAULT_PROPOSALS_PER_DAY,
    ProposalRateLimiter,
)

from conftest import BUILD_SHA, verdict_map

RS = "com.acme.shipping.RateSelector"


# -- C54 ----------------------------------------------------------------

def test_scarf_default_is_five_per_day():
    assert DEFAULT_PROPOSALS_PER_DAY == 5


def test_rate_limiter_docstring_states_the_safety_rationale():
    doc = ProposalRateLimiter.__module__
    import ax_server.analysis.ratelimit as mod

    assert "safety" in mod.__doc__.lower()
    assert "detect and remediate" in mod.__doc__


def test_limiter_blocks_new_proposals_past_the_cap():
    ledger = SqliteProposalLedger(":memory:")
    limiter = ProposalRateLimiter(ledger, per_day=2)
    limiter.begin_run()
    now = datetime(2026, 9, 12, tzinfo=UTC)
    for _ in range(2):
        assert limiter.check("svc", when=now).allowed
        limiter.consume("svc", when=now)
    decision = limiter.check("svc", when=now)
    assert not decision.allowed
    assert "C54" in decision.reason
    ledger.close()


def test_limiter_budget_resets_the_next_day():
    ledger = SqliteProposalLedger(":memory:")
    limiter = ProposalRateLimiter(ledger, per_day=1)
    limiter.begin_run()
    d1 = datetime(2026, 9, 12, tzinfo=UTC)
    limiter.consume("svc", when=d1)
    assert not limiter.check("svc", when=d1).allowed
    assert limiter.check("svc", when=d1 + timedelta(days=1)).allowed
    ledger.close()


def test_engine_defers_candidates_past_the_cap(ingested, store, manifest, suppressions, calendar):
    ledger = SqliteProposalLedger(":memory:")
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar, proposals_per_day=0),
        suppressions=suppressions,
        ledger=ledger,
    )
    run = engine.derive(BUILD_SHA)
    assert run.by_status(DEAD_CANDIDATE) == ()
    blocked = verdict_map(run)[f"{RS}#legacyFallback()V"]
    assert blocked.status == UNKNOWN
    assert any(r.startswith("rate-limited:") for r in blocked.reasons)
    engine.close()


def test_re_confirming_a_standing_proposal_is_not_rate_limited(
    ingested, store, manifest, suppressions, calendar
):
    ledger = SqliteProposalLedger(":memory:")
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar, proposals_per_day=1),
        suppressions=suppressions,
        ledger=ledger,
    )
    assert len(engine.reconcile(BUILD_SHA).by_status(DEAD_CANDIDATE)) == 1
    # Budget is now spent, but the standing claim must still be re-derived.
    assert len(engine.derive(BUILD_SHA).by_status(DEAD_CANDIDATE)) == 1
    engine.close()


# -- C55 ----------------------------------------------------------------

def test_efp_definition_is_tricorders():
    import ax_server.analysis.efp as mod

    assert "chooses not to take action" in mod.__doc__


def test_new_rule_class_starts_active():
    t = EfpTracker(":memory:")
    assert t.status("entrypoint:RequestMapping") is EfpStatus.ACTIVE
    t.close()


def test_a_few_bad_reports_do_not_disable_anything():
    t = EfpTracker(":memory:", min_reports=10)
    keys = efp_keys("RequestMapping", "OBSERVABLE")
    for _ in range(3):
        t.record_report(*keys)
        t.record_feedback(keys, actioned=False)
    assert t.status(keys[0]) is EfpStatus.ACTIVE
    t.close()


def test_ten_percent_not_useful_is_probation():
    t = EfpTracker(":memory:", min_reports=10)
    keys = efp_keys("Scheduled", "OBSERVABLE")
    for i in range(20):
        t.record_report(*keys)
        t.record_feedback(keys, actioned=i >= 3)  # 15% not useful
    stats = t.stats(keys[0])
    assert 0.10 <= stats.rate < 0.25
    assert stats.status is EfpStatus.PROBATION
    t.close()


def test_twenty_five_percent_self_disables():
    t = EfpTracker(":memory:", min_reports=10)
    keys = efp_keys("Scheduled", "OBSERVABLE")
    for i in range(20):
        t.record_report(*keys)
        t.record_feedback(keys, actioned=i >= 8)  # 40% not useful
    assert t.stats(keys[0]).status is EfpStatus.DISABLED
    assert t.disabled(keys) == keys[0]
    t.close()


def test_a_disabled_rule_class_stops_nominating(
    ingested, store, manifest, suppressions, calendar
):
    efp = EfpTracker(":memory:", min_reports=1)
    efp.force("entrypoint:RequestMapping", EfpStatus.DISABLED)
    ledger = SqliteProposalLedger(":memory:")
    engine = AnalysisEngine(
        store, manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions, ledger=ledger, efp=efp,
    )
    run = engine.derive(BUILD_SHA)
    assert run.by_status(DEAD_CANDIDATE) == ()
    blocked = verdict_map(run)[f"{RS}#legacyFallback()V"]
    assert any(r.startswith("rule-class-disabled:") for r in blocked.reasons)
    engine.close()


def test_efp_is_tracked_per_entry_point_and_eligibility_class():
    assert efp_keys("RequestMapping", "OBSERVABLE") == (
        "entrypoint:RequestMapping",
        "eligibility:OBSERVABLE",
    )


def test_reconcile_records_a_report_for_each_new_proposal(ingested, engine):
    engine.reconcile(BUILD_SHA)
    stats = {s.rule_key: s for s in engine.efp.all_stats()}
    assert stats["entrypoint:RequestMapping"].reports == 1
    assert stats["eligibility:OBSERVABLE"].reports == 1
    # Re-running must not double count a standing claim.
    engine.reconcile(BUILD_SHA)
    stats = {s.rule_key: s for s in engine.efp.all_stats()}
    assert stats["entrypoint:RequestMapping"].reports == 1
