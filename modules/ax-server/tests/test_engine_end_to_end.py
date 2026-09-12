"""Fixture end-to-end: a realistic CONTRACTS 2 payload -> merge -> verdict."""

from datetime import timedelta

import pytest

from ax_server.analysis.models import (
    DEAD_CANDIDATE,
    LIVE,
    NOT_DYNAMICALLY_OBSERVABLE,
    UNKNOWN,
    EligibilityClass,
)

from conftest import BUILD_SHA, DAY0, realistic_payload, verdict_map

RS = "com.acme.shipping.RateSelector"


@pytest.fixture
def run(ingested, engine):
    return engine.derive(BUILD_SHA)


def test_ingest_merge_verdict_pipeline(run):
    v = verdict_map(run)
    assert v[f"{RS}#pick(Ljava/util/List;)Lcom/acme/Rate;"].status == LIVE
    assert v[f"{RS}#normalise(Lcom/acme/Rate;)Lcom/acme/Rate;"].status == LIVE
    assert v[f"{RS}#legacyFallback()V"].status == DEAD_CANDIDATE
    assert v[f"{RS}#cachedLookup(Ljava/lang/String;)Lcom/acme/Rate;"].status == UNKNOWN
    assert v["com.acme.api.PublicGateway#dispatch()V"].status == UNKNOWN
    assert v["com.acme.emergency.KillSwitch#trip()V"].status == UNKNOWN
    assert v["com.acme.util.Constants#maxRetries()I"].status == NOT_DYNAMICALLY_OBSERVABLE
    assert v["com.acme.util.Constants#<clinit>()V"].status == UNKNOWN
    assert v["com.acme.shipping.NeverLoaded#run()V"].status == UNKNOWN
    assert v["com.acme.billing.MonthEndReport#render()V"].status == UNKNOWN
    assert v["com.acme.lib.LibOnlyUsedByTest#helper()V"].status == UNKNOWN


def test_exactly_one_dead_candidate_and_it_is_fully_argued(run):
    dead = run.by_status(DEAD_CANDIDATE)
    assert len(dead) == 1
    d = dead[0]
    assert (d.cls, d.method) == (RS, "legacyFallback")
    assert d.static_reachable is False
    assert d.suppressed is False
    assert d.public_api is False
    assert d.eligibility is EligibilityClass.OBSERVABLE
    assert d.phases_missing == []
    assert d.phases_covered == ["month-end", "peak-season"]
    assert d.window_days == 4
    assert d.reasons[-1].startswith("DEAD_CANDIDATE:")


def test_each_unknown_names_the_clause_that_blocked_it(run):
    v = verdict_map(run)
    cases = {
        f"{RS}#cachedLookup(Ljava/lang/String;)Lcom/acme/Rate;": "short-circuitable:",
        "com.acme.api.PublicGateway#dispatch()V": "public-api-surface:",
        "com.acme.emergency.KillSwitch#trip()V": "suppressed:",
        "com.acme.shipping.NeverLoaded#run()V": "class-never-loaded:",
        "com.acme.billing.MonthEndReport#render()V": "static-unresolved:",
        "com.acme.util.Constants#<clinit>()V": "clinit:",
    }
    for key, prefix in cases.items():
        assert any(r.startswith(prefix) for r in v[key].reasons), key


def test_last_seen_is_populated_for_live_methods(run):
    live = verdict_map(run)[f"{RS}#pick(Ljava/util/List;)Lcom/acme/Rate;"]
    assert live.last_seen is not None
    assert live.last_seen == DAY0 + timedelta(days=4)


def test_a_single_new_observation_revokes_the_candidate(ingested, engine):
    before = engine.reconcile(BUILD_SHA)
    assert len(before.by_status(DEAD_CANDIDATE)) == 1
    proposals = engine.ledger.active(BUILD_SHA)
    assert len(proposals) == 1
    assert proposals[0].ref.name == "legacyFallback"

    # C53: the claim is re-derived every run and aborted the moment it stops
    # holding. One probe bit is enough.
    from conftest import probes

    body = realistic_payload(4)
    body["coverage"][0]["probes"] = probes(0, 1, 2)
    ingested.ingest(body)

    after = engine.reconcile(BUILD_SHA)
    assert after.by_status(DEAD_CANDIDATE) == ()
    assert [ref.name for ref, _ in after.revoked] == ["legacyFallback"]
    assert engine.ledger.active(BUILD_SHA) == []
    revoked = engine.ledger.get(BUILD_SHA, proposals[0].ref)
    assert revoked is not None and revoked.active is False
    assert "LIVE" in revoked.revoked_reason


def test_first_proposed_at_is_stable_across_runs(ingested, engine):
    first = engine.reconcile(BUILD_SHA).by_status(DEAD_CANDIDATE)[0]
    assert first.first_proposed_at is not None
    second = engine.reconcile(BUILD_SHA).by_status(DEAD_CANDIDATE)[0]
    assert second.first_proposed_at == first.first_proposed_at


def test_a_missing_phase_wipes_out_every_candidate(ingested, engine):
    from ax_server.analysis.phases import PhaseCalendar

    engine.config = type(engine.config)(
        phase_calendar=PhaseCalendar(
            required=("year-end-close",), occurrences=(), recurring=()
        )
    )
    run = engine.derive(BUILD_SHA)
    assert run.by_status(DEAD_CANDIDATE) == ()
    assert all(v.phases_missing == ["year-end-close"] for v in run.verdicts)


def test_degraded_windows_are_excluded_from_the_death_argument(collector, engine):
    for day in range(4):
        body = realistic_payload(day)
        body["agentHealth"]["degraded"] = True
        collector.ingest(body)
    run = engine.derive(BUILD_SHA)
    assert run.evidence.usable_windows == ()
    assert len(run.evidence.excluded_windows) == 4
    assert run.by_status(DEAD_CANDIDATE) == ()
    # A set probe is still trustworthy: a degraded agent drops, never invents.
    assert verdict_map(run)[f"{RS}#pick(Ljava/util/List;)Lcom/acme/Rate;"].status == LIVE


def test_summary_refuses_to_quote_a_precision_number(run):
    summary = run.summary()
    assert "false-negative-biased" in summary["precisionPosture"]
    assert summary["counts"][DEAD_CANDIDATE] == 1
    assert summary["windowDays"] == 4


def test_nothing_in_the_engine_can_delete(engine):
    import inspect

    import ax_server.analysis.engine as mod

    source = inspect.getsource(mod)
    assert "os.remove" not in source and "shutil.rmtree" not in source
    assert "DROP TABLE" not in source
