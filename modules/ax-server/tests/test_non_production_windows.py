"""BUG #22b -- the C50 gate stores and marks instead of 403ing.

From the field trial on a 690-class Spring Boot service:

    "With `ax.environment` unset, the agent sets `livenessEvidence=false` and
    the collector 403s every window (`not_production_classified`). The
    allowlist is hardcoded (`production`, `prod`) in `collector/classification.py`
    with no CLI flag. I got 0 windows stored and had to mislabel a laptop as
    production to see anything. This is the single biggest barrier to anyone
    trying it."

The safety property C50 buys is that a TEST JVM's coverage can never be read
as evidence that code is alive. That property lives in what a window may be
evidence FOR, not in whether its row exists -- so the whole of this file is
two claims held at once:

  1. the data arrives, is stored, is counted and is explained; and
  2. it is still evidence for absolutely nothing, in BOTH directions -- it can
     neither create a DEAD_CANDIDATE nor revoke one.

Claim 2 is the one that matters. A window that could only fail to prove life
would still be dangerous: `class_loaded` and the installed-probe mask make a
nomination MORE likely, so a laptop that merely loaded a class could help
manufacture a candidate for it if either were merged.
"""

import json
import urllib.error
import urllib.request

import pytest

from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.models import DEAD_CANDIDATE, UNKNOWN
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.api.service import QueryService
from ax_server.collector.classification import (
    DEFAULT_PRODUCTION_ENVIRONMENTS,
    EnvironmentPolicy,
    parse_environments,
)
from ax_server.collector.errors import IngestRejected
from ax_server.collector.health import RejectReason
from ax_server.collector.service import CollectorService

from conftest import (
    BUILD_SHA,
    realistic_payload,
    realistic_payload_installed,
    unclassified_payload,
    verdict_map,
)

RS = "com.acme.shipping.RateSelector"
DEAD = "com.acme.shipping.RateSelector#legacyFallback()V"


# ======================================================================
# 1. the window is STORED, not refused
# ======================================================================


def test_an_unclassified_window_is_stored_and_marked(collector):
    result = collector.ingest(unclassified_payload(0))

    assert result.accepted is True
    assert result.production is False
    assert result.liveness_evidence is False
    assert result.environment == "unclassified"

    windows = collector.store.observed_windows(BUILD_SHA)
    assert len(windows) == 1, "the whole point: the row exists"
    assert windows[0].production is False
    assert windows[0].liveness_evidence is False
    assert windows[0].environment == "unclassified"


@pytest.mark.parametrize("env", ["staging", "ci", "qa", "canary", "dev", "laptop"])
def test_a_non_production_label_is_stored_and_marked(collector, env):
    body = realistic_payload(0)
    body["jvmClassification"] = {"env": env}
    result = collector.ingest(body)
    assert result.accepted and result.production is False
    window = collector.store.observed_windows(BUILD_SHA)[0]
    assert window.environment == env
    assert window.production is False


def test_it_is_counted_in_its_own_bucket_not_as_a_reject(collector):
    collector.ingest(unclassified_payload(0))
    snap = collector.health.snapshot()
    assert snap["windowsAccepted"] == 1
    assert snap["windowsRejected"] == 0
    assert snap["windowsNonProduction"] == 1
    assert snap["windowsProductionClassified"] == 0
    assert snap["nonProductionEnvironments"] == {"unclassified": 1}
    # Not filed under a reject reason: it was not rejected.
    assert RejectReason.NOT_PRODUCTION not in snap["rejectsByReason"]


def test_the_response_says_what_was_withheld_and_why(collector):
    body = collector.ingest(unclassified_payload(0)).to_json()
    withheld = body["nonProductionWithheld"]
    # Six coverage records, two set probe bits, six loaded classes.
    assert withheld["coverageRecords"] == 6
    assert withheld["probeBits"] == 2
    assert withheld["classesLoaded"] == 6
    assert body["livenessEvidence"] is False
    assert "evidence for nothing" in body["livenessEvidenceNote"]
    assert "--allow-environments" in body["livenessEvidenceNote"]


# ======================================================================
# 2. ...and it is evidence for NOTHING
# ======================================================================


def test_nothing_from_it_is_merged_as_evidence(collector):
    collector.ingest(unclassified_payload(0))
    store = collector.store
    # Coverage: not merged. `probes` had bits 0 and 1 set for RateSelector.
    assert store.coverage(BUILD_SHA, RS) is None
    # C10 loaded-class evidence: not recorded. This one is load bearing in the
    # OTHER direction -- `class_loaded` REMOVES the `class-never-loaded`
    # blocker, so a laptop writing here would help manufacture a candidate.
    assert store.class_loaded_ever(BUILD_SHA, RS) is False


def test_the_installed_probe_mask_is_not_merged_either(collector):
    """Bug #18's mask ENABLES death evidence, so #22b must withhold it too.

    `probesInstalled` set at an index is what makes an unset coverage bit
    count as a real observation of non-execution. A non-production JVM must
    not be able to arm that.
    """
    body = realistic_payload_installed(0)
    del body["jvmClassification"]
    collector.ingest(body)
    assert collector.store.probes_installed(BUILD_SHA, RS) is None
    assert collector.store.installed_masks(BUILD_SHA) == {}


def test_edges_from_a_non_production_window_are_not_merged(edge_collector):
    """CONTRACTS 2 v3: an edge "is evidence of liveness only, and only under
    the same C50 gate as everything else in the window" -- and an observed
    inbound edge short-circuits straight to LIVE."""
    from conftest import realistic_payload_v3

    body = realistic_payload_v3(0)
    del body["jvmClassification"]
    result = edge_collector.ingest(body)
    assert result.accepted and result.production is False
    assert result.edges_merged == 0
    assert result.edges_withheld == 3
    assert edge_collector.store.hot_edges(BUILD_SHA) == []


def test_a_non_production_window_cannot_create_a_dead_candidate(
    collector, store, manifest, suppressions, calendar
):
    """The headline safety claim, in the direction people forget.

    Four full days of unclassified windows: every class loaded, every probe
    reported, phases covered. If ANY of that had merged, `legacyFallback`
    would be nominated -- it is the one method the production fixture nominates
    on exactly this data.
    """
    for day in range(4):
        collector.ingest(unclassified_payload(day))

    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions,
        ledger=SqliteProposalLedger(":memory:"),
    )
    try:
        run = engine.reconcile()
        assert run.by_status(DEAD_CANDIDATE) == (), "a laptop nominated something"
        verdict = verdict_map(run)[DEAD]
        assert verdict.status == UNKNOWN
        reasons = " ".join(verdict.reasons)
        assert "no-usable-window" in reasons
        assert "class-never-loaded" in reasons
        # And the ledger is empty: nothing was ever proposed, so nothing can be
        # confirmed later on the strength of this data.
        assert engine.ledger.active(BUILD_SHA) == []
    finally:
        engine.close()


def test_a_non_production_window_cannot_revoke_a_dead_candidate(
    collector, store, manifest, suppressions, calendar
):
    """The other half of "can neither create nor revoke" (C53).

    A standing proposal is revoked by any NEW OBSERVATION. A non-production
    window carries observations -- `probes` with bits set for the very method
    under proposal -- and must not be allowed to count as one, or a single
    unlabelled JVM could silently retract every candidate in the build.
    """
    for day in range(4):
        collector.ingest(realistic_payload(day))

    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions,
        ledger=SqliteProposalLedger(":memory:"),
    )
    try:
        first = engine.reconcile()
        assert [f"{v.cls}#{v.method}{v.desc}" for v in first.by_status(DEAD_CANDIDATE)] == [DEAD]
        proposed_at = engine.ledger.get(BUILD_SHA, first.by_status(DEAD_CANDIDATE)[0].ref)
        assert proposed_at is not None and proposed_at.active

        # Now a non-production JVM reports the candidate method as EXECUTED.
        revoking = unclassified_payload(4)
        for rec in revoking["coverage"]:
            if rec["class"] == RS:
                # bits 0,1,2 -- idx 2 is legacyFallback, the standing candidate
                from conftest import probes as probe_bits

                rec["probes"] = probe_bits(0, 1, 2)
        assert collector.ingest(revoking).production is False

        second = engine.reconcile()
        assert second.revoked == (), "an unclassified JVM revoked a standing claim"
        assert [f"{v.cls}#{v.method}{v.desc}" for v in second.by_status(DEAD_CANDIDATE)] == [DEAD]
        still = engine.ledger.get(BUILD_SHA, first.by_status(DEAD_CANDIDATE)[0].ref)
        assert still is not None and still.active
    finally:
        engine.close()


def test_window_models_still_exclude_it(collector):
    """The two gates the brief asks to be verified, read directly."""
    collector.ingest(unclassified_payload(0))
    window = collector.store.observed_windows(BUILD_SHA)[0]
    assert window.usable_as_death_evidence is False
    assert window.usable_as_life_evidence is False
    assert window.non_production is True


def test_an_agent_livenessevidence_false_overrides_a_production_label(collector):
    """Fail-closed on BOTH halves.

    CONTRACTS 2 makes `livenessEvidence` the gate and makes it the agent's
    assertion. If the agent disclaims its own window we believe it, even when
    the environment label would have passed -- it is the side that actually saw
    the JVM.
    """
    body = realistic_payload(0)
    body["agentHealth"]["livenessEvidence"] = False
    result = collector.ingest(body)
    assert result.accepted and result.production is False
    assert collector.store.coverage(BUILD_SHA, RS) is None


def test_an_absent_livenessevidence_is_not_read_as_false(collector):
    """A pre-C50 agent says nothing, and silence is not a disclaimer."""
    body = realistic_payload(0)
    assert "livenessEvidence" not in body["agentHealth"]
    assert collector.ingest(body).production is True


def test_testrunnerdetected_is_still_a_refusal(collector):
    """CONTRACTS 2: "a window with ... `testRunnerDetected: true` is discarded
    at ingest and counted". #22b softens the livenessEvidence half only -- a
    positive assertion that a test harness is running is C50.3 and stays a
    403, exactly as a test-runner frame in `classesLoaded` does."""
    body = realistic_payload(0)
    body["agentHealth"]["testRunnerDetected"] = True
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.TEST_RUNNER_WINDOW
    assert exc.value.status == 403
    assert collector.store.coverage(BUILD_SHA, RS) is None


# ======================================================================
# 3. --allow-environments
# ======================================================================


def test_allow_environments_widens_the_allowlist(store):
    policy = EnvironmentPolicy(allow_environments=("prod-eu", "live"))
    collector = CollectorService(store, policy=policy)
    body = realistic_payload(0)
    body["jvmClassification"] = {"env": "prod-eu"}

    result = collector.ingest(body)
    assert result.production is True
    assert result.environment == "prod-eu"
    assert collector.store.coverage(BUILD_SHA, RS) is not None
    window = collector.store.observed_windows(BUILD_SHA)[0]
    assert window.usable_as_death_evidence is True


def test_allow_environments_extends_rather_than_replaces(store):
    """Adding "prod-eu" must not drop "production" on the way in."""
    policy = EnvironmentPolicy(allow_environments=("prod-eu",))
    assert policy.allowed == {"production", "prod", "prod-eu"}
    assert set(DEFAULT_PRODUCTION_ENVIRONMENTS) <= policy.allowed
    assert CollectorService(store, policy=policy).ingest(realistic_payload(0)).production


def test_allow_environments_does_not_widen_to_other_labels(store):
    collector = CollectorService(
        store, policy=EnvironmentPolicy(allow_environments=("prod-eu",))
    )
    body = realistic_payload(0)
    body["jvmClassification"] = {"env": "staging"}
    assert collector.ingest(body).production is False


def test_the_cli_spec_is_parsed_and_cannot_allow_the_empty_label():
    assert parse_environments("prod-eu, live ,PROD-US") == ("prod-eu", "live", "prod-us")
    assert parse_environments("") == ()
    assert parse_environments(None) == ()
    # `--allow-environments "prod,"` must not make an unlabelled JVM production.
    assert "" not in parse_environments("prod,")
    assert EnvironmentPolicy(allow_environments=parse_environments("prod,")).allowed == {
        "production",
        "prod",
    }


# ======================================================================
# 4. --reject-unclassified restores the 403
# ======================================================================


def test_reject_unclassified_restores_the_403(strict_collector):
    with pytest.raises(IngestRejected) as exc:
        strict_collector.ingest(unclassified_payload(0))
    assert exc.value.reason == RejectReason.NOT_PRODUCTION
    assert exc.value.status == 403
    assert strict_collector.store.observed_windows(BUILD_SHA) == []
    assert strict_collector.health.snapshot()["windowsRejected"] == 1


def test_reject_unclassified_also_rejects_a_non_production_label(strict_collector):
    body = realistic_payload(0)
    body["jvmClassification"] = {"env": "staging"}
    with pytest.raises(IngestRejected) as exc:
        strict_collector.ingest(body)
    assert exc.value.reason == RejectReason.NOT_PRODUCTION


def test_reject_unclassified_is_off_by_default():
    assert EnvironmentPolicy().reject_non_production is False
    assert EnvironmentPolicy(reject_non_production=True).reject_non_production is True


def test_strict_mode_still_accepts_production(strict_collector):
    assert strict_collector.ingest(realistic_payload(0)).accepted


# ======================================================================
# 5. it is VISIBLE: agent_health, the run summary, HTTP
# ======================================================================


@pytest.fixture
def unclassified_api(collector, store, manifest, suppressions, calendar):
    for day in range(4):
        collector.ingest(unclassified_payload(day))
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions,
        ledger=SqliteProposalLedger(":memory:"),
    )
    yield QueryService(engine, collector=collector)
    engine.close()


def test_agent_health_shows_the_non_production_count_and_explains_it(unclassified_api):
    health = unclassified_api.agent_health(BUILD_SHA)
    assert health["windows"] == 4
    assert health["nonProductionWindows"] == 4
    assert health["windowsUsableAsDeathEvidence"] == 0
    assert health["livenessEvidenceWindows"] == 0
    assert health["nonProductionEnvironments"] == {"unclassified": 4}
    assert health["environments"] == {"unclassified": 4}

    gate = health["evidenceGate"]
    assert "NON-PRODUCTION" in gate
    assert "Data IS arriving and being stored" in gate
    assert "ax.environment=production" in gate
    # These windows carry NO label, so there is nothing to allowlist -- and
    # `unclassified` is OUR placeholder, never a value to suggest. Offering
    # `--allow-environments unclassified` would re-create C50 wholesale: every
    # unlabelled JVM, CI included, would count as production.
    assert "nothing to allowlist" in gate
    assert "--allow-environments unclassified" not in gate
    assert "evidence for nothing" in health["nonProductionNote"]

    ingest = health["ingest"]
    assert ingest["windowsNonProduction"] == 4
    assert ingest["nonProductionWithheld"]["coverageRecords"] == 24


def test_a_real_label_gets_the_allow_environments_fix(collector, store, manifest,
                                                       suppressions, calendar):
    """A JVM that DOES report a label gets told the exact flag to pass."""
    for day in range(2):
        body = realistic_payload(day)
        body["jvmClassification"] = {"env": "staging"}
        collector.ingest(body)
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions,
        ledger=SqliteProposalLedger(":memory:"),
    )
    try:
        gate = QueryService(engine, collector=collector).agent_health(BUILD_SHA)["evidenceGate"]
        assert "--allow-environments staging" in gate
    finally:
        engine.close()


def test_the_run_summary_shows_it_too(unclassified_api):
    summary = unclassified_api.summary(BUILD_SHA)
    windows = summary["windows"]
    assert windows["stored"] == 4
    assert windows["nonProduction"] == 4
    assert windows["usableAsDeathEvidence"] == 0
    assert windows["nonProductionEnvironments"] == {"unclassified": 4}
    assert "NOT production-classified" in windows["note"]
    assert "no DEAD_CANDIDATE can be produced" in windows["note"]
    assert summary["counts"].get("DEAD_CANDIDATE") is None


def test_coverage_windows_lists_it_as_excluded(unclassified_api):
    payload = unclassified_api.coverage_windows(BUILD_SHA)
    assert payload["usable"] == []
    assert len(payload["excluded"]) == 4
    assert payload["excluded"][0]["usableAsDeathEvidence"] is False
    assert payload["excluded"][0]["production"] is False


def test_a_production_build_says_so(api):
    """The same surfaces on a healthy build must not cry wolf."""
    health = api.agent_health(BUILD_SHA)
    assert health["nonProductionWindows"] == 0
    assert health["windowsUsableAsDeathEvidence"] == 4
    assert "All 4 window(s) are production-classified" in health["evidenceGate"]
    assert api.summary(BUILD_SHA)["windows"]["nonProduction"] == 0


@pytest.fixture
def api(ingested, engine):
    return QueryService(engine, collector=ingested)


@pytest.fixture
def server(store, collector, engine):
    """The full stack, default (store-and-mark) mode."""
    import threading

    from ax_server.api.http import make_api_router
    from ax_server.collector.http import Router, make_collector_router, serve

    router = (
        Router()
        .extend(make_collector_router(collector))
        .extend(make_api_router(QueryService(engine, collector=collector)))
    )
    httpd = serve(router, port=0)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{httpd.server_address[1]}"
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def test_http_accepts_an_unclassified_jvm_with_202(server):
    """The trial's exact experience, over HTTP: no more 403 wall."""
    req = urllib.request.Request(
        server + "/v1/ingest",
        data=json.dumps(unclassified_payload(0)).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        assert resp.status == 202
        body = json.loads(resp.read())
    assert body["accepted"] is True
    assert body["livenessEvidence"] is False
    assert body["classesMerged"] == 0

    with urllib.request.urlopen(
        server + f"/v1/builds/{BUILD_SHA}/agent-health", timeout=10
    ) as resp:
        health = json.loads(resp.read())
    assert health["windows"] == 1
    assert health["nonProductionWindows"] == 1
