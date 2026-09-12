"""The SCOPE-v3 sampled runtime call-edge tier, end to end.

Every test here guards one of four properties, and all four are places where
getting it wrong manufactures a wrong answer rather than an error:

1. **Counts are additive; coverage is an idempotent OR.** Conflating them
   either discards volume or invents it.
2. **A dangling `(class, idx)` is refused, loudly.** A stored edge that points
   at a non-existent identity is unresolvable forever.
3. **Absence of an edge is never evidence.** The tier samples 1-in-N root
   entries; a hot method can legitimately report zero edges.
4. **Presence of an edge is proof of life.** It really happened.
"""

import json
from datetime import timedelta

import pytest

from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.manifest import ClassEntry, MethodEntry
from ax_server.analysis.models import DEAD_CANDIDATE, LIVE, UNKNOWN, EligibilityClass, MethodRef
from ax_server.analysis.phases import PhaseCoverage
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.analysis.rules import MethodFacts, evaluate
from ax_server.analysis.runtime_edges import RuntimeCallGraph, manifest_method_index
from ax_server.api.service import QueryService
from ax_server.collector.errors import IngestRejected
from ax_server.collector.health import RejectReason
from ax_server.collector.known_methods import UNCHECKED, MethodIndex
from ax_server.collector.service import CollectorService
from ax_server.mcp.server import McpServer
from ax_server.store.bitset import set_bits
from ax_server.store.models import EdgeRecord

from conftest import (
    BUILD_SHA,
    DAY0,
    DEFAULT_EDGES,
    GATEWAY,
    RS,
    payload,
    probes,
    realistic_payload,
    realistic_payload_v3,
    verdict_map,
)

PICK = "pick(Ljava/util/List;)Lcom/acme/Rate;"
NORMALISE = "normalise(Lcom/acme/Rate;)Lcom/acme/Rate;"
MONTH_END = "com.acme.billing.MonthEndReport"


# ======================================================================
# 1. store: persistence, aggregation, both query directions
# ======================================================================


def _window_with_edges(collector, day=0, pod="pod-a", edges=None):
    return collector.ingest(realistic_payload_v3(day, instance_id=pod, edges=edges))


def test_edges_are_persisted_and_queryable_in_both_directions(edge_collector):
    _window_with_edges(edge_collector)
    store = edge_collector.store

    callers = store.callers_of(BUILD_SHA, RS, 1)
    assert {(c.caller_cls, c.caller_idx) for c in callers} == {(RS, 0), (RS, 3)}

    callees = store.callees_of(BUILD_SHA, RS, 0)
    assert [(c.callee_cls, c.callee_idx) for c in callees] == [(RS, 1)]

    # The gateway is a caller of pick, and pick is not a caller of the gateway.
    assert [(c.caller_cls, c.caller_idx) for c in store.callers_of(BUILD_SHA, RS, 0)] == [
        (GATEWAY, 0)
    ]
    assert store.callees_of(BUILD_SHA, GATEWAY, 0)[0].callee == (RS, 0)


def test_both_directions_use_an_index_not_a_table_scan(edge_collector):
    _window_with_edges(edge_collector)
    conn = edge_collector.store._conn
    for where in ("callee_cls=? AND callee_idx=?", "caller_cls=? AND caller_idx=?"):
        plan = conn.execute(
            f"EXPLAIN QUERY PLAN SELECT * FROM edge_agg WHERE build_sha=? AND {where}",
            (BUILD_SHA, RS, 1),
        ).fetchall()
        detail = " ".join(str(row["detail"]) for row in plan)
        assert "SCAN" not in detail, detail


def test_first_and_last_seen_track_the_window_span(edge_collector):
    _window_with_edges(edge_collector, day=0)
    _window_with_edges(edge_collector, day=3)
    edge = edge_collector.store.callers_of(BUILD_SHA, RS, 0)[0]
    assert edge.first_seen == DAY0 + timedelta(days=1)
    assert edge.last_seen == DAY0 + timedelta(days=4)
    assert edge.windows == 2


def test_hot_edges_rank_by_observed_count(edge_collector):
    _window_with_edges(edge_collector)
    hot = edge_collector.store.hot_edges(BUILD_SHA)
    assert [e.sampled_observations for e in hot] == [7, 5, 3]


def test_sample_rate_is_stored_with_the_counts(edge_collector):
    _window_with_edges(edge_collector)
    assert edge_collector.store.edge_sample_rates(BUILD_SHA) == {1024: 1}
    edge = edge_collector.store.callers_of(BUILD_SHA, RS, 0)[0]
    assert edge.uniform_sample_rate == 1024


def test_mixed_sample_rates_refuse_to_report_a_single_scale(edge_collector):
    _window_with_edges(edge_collector, day=0)
    body = realistic_payload_v3(1)
    body["agentHealth"]["edgesSampleRate"] = 256
    edge_collector.ingest(body)
    edge = edge_collector.store.callers_of(BUILD_SHA, RS, 0)[0]
    assert (edge.sample_rate_min, edge.sample_rate_max) == (256, 1024)
    # A sum of counts taken at two scales has no single scale.
    assert edge.uniform_sample_rate is None


def test_an_empty_edges_array_is_not_the_same_as_an_absent_key(edge_collector):
    edge_collector.ingest(realistic_payload_v3(0, edges=[]))
    # The tier reported; it reported nothing. That is a fact about the tier.
    assert edge_collector.store.edge_sample_rates(BUILD_SHA) == {1024: 1}

    edge_collector.ingest(realistic_payload(1))  # no `edges` key at all
    assert edge_collector.store.edge_sample_rates(BUILD_SHA) == {1024: 1}


# ======================================================================
# 2. collector: additive counts vs idempotent OR, dangling rejection
# ======================================================================


def test_counts_are_additive_while_coverage_is_an_idempotent_or(edge_collector):
    """THE distinction. `coverage[].probes` is an accumulated bitset, so
    re-reporting it changes nothing; `edges[].count` is a per-window delta, so
    re-reporting it adds. Same pipeline, opposite merge semantics."""
    for day in (0, 1):
        for pod in ("pod-a", "pod-b"):
            _window_with_edges(edge_collector, day=day, pod=pod)

    store = edge_collector.store
    # Two windows x two pods = 4 reports of the same graph.
    assert store.callees_of(BUILD_SHA, RS, 0)[0].sampled_observations == 5 * 4
    assert store.callers_of(BUILD_SHA, RS, 0)[0].sampled_observations == 7 * 4
    # ...and the coverage bitset is exactly what one window reported.
    assert sorted(set_bits(store.coverage(BUILD_SHA, RS))) == [0, 1]


def test_replaying_one_identical_window_doubles_edges_and_not_coverage(edge_collector):
    body = realistic_payload_v3(0)
    edge_collector.ingest(body)
    before = edge_collector.store.coverage(BUILD_SHA, RS)
    edge_collector.ingest(dict(body))
    assert edge_collector.store.callers_of(BUILD_SHA, RS, 0)[0].sampled_observations == 14
    assert edge_collector.store.coverage(BUILD_SHA, RS) == before


def test_counts_are_never_or_merged(edge_collector):
    """A regression guard with teeth: OR-ing 7 and 7 gives 7, summing gives
    14. If someone ever routes edges through `merge_coverage`-style logic this
    is the test that notices."""
    edge_collector.ingest(realistic_payload_v3(0, instance_id="pod-a"))
    edge_collector.ingest(realistic_payload_v3(0, instance_id="pod-b"))
    observed = edge_collector.store.callers_of(BUILD_SHA, RS, 0)[0].sampled_observations
    assert observed == 14
    assert observed != 7


def test_dangling_edge_endpoint_is_rejected_loudly(edge_collector, caplog):
    body = realistic_payload_v3(
        0, edges=[{"fromClass": RS, "fromIdx": 0, "toClass": RS, "toIdx": 99, "count": 4}]
    )
    with caplog.at_level("ERROR"):
        with pytest.raises(IngestRejected) as exc:
            edge_collector.ingest(body)
    assert exc.value.reason == RejectReason.UNKNOWN_EDGE_ENDPOINT
    assert "dangling" in exc.value.detail
    assert f"{RS}#99" in exc.value.detail
    assert "INGEST REJECTED" in caplog.text
    snap = edge_collector.health.snapshot()
    assert snap["rejectsByReason"][RejectReason.UNKNOWN_EDGE_ENDPOINT] == 1
    # Nothing was stored -- not the edge, and not the window either.
    assert edge_collector.store.hot_edges(BUILD_SHA) == []
    assert edge_collector.store.observed_windows(BUILD_SHA) == []
    assert edge_collector.store.rejects(BUILD_SHA)[0]["reason"] == (
        RejectReason.UNKNOWN_EDGE_ENDPOINT
    )


def test_dangling_caller_is_rejected_too(edge_collector):
    body = realistic_payload_v3(
        0,
        edges=[
            {"fromClass": "com.acme.ghost.NotInManifest", "fromIdx": 0,
             "toClass": RS, "toIdx": 0, "count": 1}
        ],
    )
    with pytest.raises(IngestRejected) as exc:
        edge_collector.ingest(body)
    assert exc.value.reason == RejectReason.UNKNOWN_EDGE_ENDPOINT
    assert "edges[].from" in exc.value.detail


def test_a_collector_without_a_manifest_counts_what_it_could_not_check(collector):
    """Not "everything is valid" -- "we could not look", and it is counted."""
    result = collector.ingest(realistic_payload_v3(0))
    assert result.accepted
    assert result.edge_endpoints_verified is False
    assert collector.health.snapshot()["edgeWindowsUnverified"] == 1
    assert collector.health.snapshot()["rejectsByReason"][RejectReason.EDGES_UNVERIFIED] == 1


def test_the_index_only_answers_for_the_build_it_describes(manifest):
    index = manifest_method_index(manifest)
    assert index.knows_build(BUILD_SHA)
    assert not index.knows_build("some-other-sha")
    assert index.has_method(BUILD_SHA, RS, 0)
    assert not index.has_method(BUILD_SHA, RS, 99)
    # A different build is "no opinion", not "absent".
    assert not index.has_method("other", RS, 0)
    assert UNCHECKED.knows_build(BUILD_SHA) is False


def test_edges_from_an_unknown_build_are_not_rejected(edge_collector):
    """A collector holding build A's manifest has NOT found a dangling edge in
    build B's window; it has failed to look. Rejecting would throw away real
    coverage for a build it simply does not know."""
    body = realistic_payload_v3(0)
    body["buildSha"] = "a-different-build"
    result = edge_collector.ingest(body)
    assert result.accepted and result.edge_endpoints_verified is False


def test_edge_health_counters_are_decoded_and_persisted(edge_collector):
    edge_collector.ingest(realistic_payload_v3(0))
    window = edge_collector.store.observed_windows(BUILD_SHA)[0]
    eh = window.agent_health.edges
    assert eh.enabled is True
    assert eh.sample_rate == 1024
    assert eh.sampled_roots == 41
    assert eh.recorded == 15
    assert eh.truncated_depth == 1
    assert eh.lossy is True  # a depth truncation IS a lost edge


def test_a_latched_off_edge_tier_does_not_degrade_the_window(edge_collector):
    """CONTRACTS 2 v3: `edgeTierFailures` is deliberately NOT `degraded` --
    coverage and tier-2 in the same window are still valid evidence."""
    body = realistic_payload_v3(0, edges=[])
    body["agentHealth"]["edgeTierFailures"] = 3
    result = edge_collector.ingest(body)
    assert result.accepted and result.degraded is False
    window = edge_collector.store.observed_windows(BUILD_SHA)[0]
    assert window.agent_health.edges.tier_failures == 3
    assert window.degraded is False
    assert window.usable_as_death_evidence is True


def test_a_non_power_of_two_sample_rate_is_refused(edge_collector):
    body = realistic_payload_v3(0)
    body["agentHealth"]["edgesSampleRate"] = 1000
    with pytest.raises(IngestRejected) as exc:
        edge_collector.ingest(body)
    assert "power of two" in exc.value.detail


def test_counts_without_a_declared_sample_rate_are_refused(edge_collector):
    body = realistic_payload_v3(0)
    del body["agentHealth"]["edgesSampleRate"]
    with pytest.raises(IngestRejected) as exc:
        edge_collector.ingest(body)
    assert "uninterpretable" in exc.value.detail


def test_a_derived_rate_on_an_edge_is_refused(edge_collector):
    body = realistic_payload_v3(0)
    body["edges"][0]["callsPerSecond"] = 12.5
    with pytest.raises(IngestRejected):
        edge_collector.ingest(body)


def test_negative_counters_are_refused(edge_collector):
    body = realistic_payload_v3(0)
    body["agentHealth"]["edgesDropped"] = -1
    with pytest.raises(IngestRejected):
        edge_collector.ingest(body)


def test_a_v2_payload_still_ingests_and_reports_no_edge_tier(edge_collector):
    result = edge_collector.ingest(realistic_payload(0))
    assert result.accepted
    assert result.edges_reported is False and result.edges_enabled is False
    assert result.edges_merged == 0
    assert edge_collector.store.hot_edges(BUILD_SHA) == []


def test_ingest_result_never_calls_a_sampled_count_a_call_total(edge_collector):
    body = json.dumps(realistic_payload_v3(0)).encode()
    result = edge_collector.ingest_bytes(body)
    rendered = result.to_json()
    assert rendered["edgeSampledObservations"] == 15
    assert rendered["edgesSampleRate"] == 1024
    assert "not calls" in rendered["edgeCountNote"]
    # No field in the ingest ack invites the number to be read as a call
    # total: `classesMerged`/`probesNewlySet` are exact, the edge figure is
    # named `edgeSampledObservations` and nothing is named `edgeCalls`.
    assert not any("call" in k.lower() for k in rendered)


def test_a_store_without_the_edge_port_discards_edges_loudly(manifest, caplog):
    class NoEdges:
        """A `Store` adapter that does not implement `EdgeStore`."""

        def __init__(self):
            self.windows = []

        def record_window(self, w):
            self.windows.append(w)

        def merge_coverage(self, *a, **k):
            pass

        def coverage(self, *a, **k):
            return None

    collector = CollectorService(
        NoEdges(), known_methods=manifest_method_index(manifest)  # type: ignore[arg-type]
    )
    with caplog.at_level("WARNING"):
        result = collector.ingest(realistic_payload_v3(0))
    assert result.accepted and result.edges_merged == 0
    assert "DISCARDED" in caplog.text


# ======================================================================
# 3. analysis: absence is never evidence, presence is proof of life
# ======================================================================


_COMPLETE = PhaseCoverage(covered=("month-end",), missing=(), window_days=90, span=None)


def _facts(**over):
    """The `test_rules` baseline: a DEAD_CANDIDATE unless a clause flips."""
    method = MethodEntry(
        idx=0,
        name="legacyFallback",
        desc="()V",
        access="private",
        eligibility=over.pop("eligibility", EligibilityClass.OBSERVABLE),
        short_circuitable=over.pop("short_circuitable", False),
    )
    klass = ClassEntry(
        name="com.acme.A", is_public_api=over.pop("public_api", False), methods=(method,)
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
        phase_coverage=_COMPLETE,
        usable_windows=4,
        excluded_windows=0,
    )
    base.update(over)
    return MethodFacts(**base)


def test_zero_observed_edges_never_promotes_anything_to_dead_candidate():
    """The rule the whole tier hangs on.

    A method with zero observed edges and every OTHER dead-code clause
    passing must be decided exactly as it would be with no edge tier at all.
    Absence contributes nothing -- it cannot complete a nomination, and it
    cannot even be listed as a blocker.
    """
    armed = evaluate(_facts(runtime_edges_reported=True, runtime_inbound_edges=0))
    silent = evaluate(_facts(runtime_edges_reported=False))

    # Identical decision, identical blockers: the tier changed nothing.
    assert armed.status == silent.status
    assert armed.blockers == silent.blockers

    # And the one reason it did add is explicitly marked as non-evidence.
    absence = [r for r in armed.reasons if r.startswith("runtime-edge-absent:")]
    assert len(absence) == 1
    assert "NOT evidence" in absence[0]
    assert "runtime-edge-absent" not in armed.blockers


def test_absence_cannot_substitute_for_any_missing_clause():
    """Zero edges must not stand in for a clause that did not pass.

    Each flip below leaves the method UNKNOWN on its own; arming a tier that
    observed nothing must not turn any of them into a nomination.
    """
    flips = [
        {"static_reachable": None},
        {"static_reachable": True},
        {"class_loaded": False},
        {"public_api": True},
        {"usable_windows": 0},
        {"short_circuitable": True},
    ]
    for flip in flips:
        outcome = evaluate(_facts(runtime_edges_reported=True, runtime_inbound_edges=0, **flip))
        assert outcome.status == UNKNOWN, flip
        assert outcome.status != DEAD_CANDIDATE, flip


def test_absence_changes_no_verdict_anywhere_in_the_clause_space():
    """The invariant, checked exhaustively rather than by example.

    Over every combination of the clauses that can block a nomination, arming
    an edge tier that observed nothing must produce the same status and the
    same blockers as no edge tier at all.
    """
    import itertools

    options = [
        ("static_reachable", (False, True, None)),
        ("class_loaded", (True, False)),
        ("public_api", (False, True)),
        ("short_circuitable", (False, True)),
        ("usable_windows", (4, 0)),
    ]
    keys = [name for name, _ in options]
    for combo in itertools.product(*(values for _, values in options)):
        flip = dict(zip(keys, combo, strict=True))
        armed = evaluate(_facts(runtime_edges_reported=True, runtime_inbound_edges=0, **flip))
        silent = evaluate(_facts(**flip))
        assert armed.status == silent.status, flip
        assert armed.blockers == silent.blockers, flip


def test_no_clause_in_the_rule_source_reads_edge_absence():
    """Structural guard. The enforcement of "absence is not evidence" is the
    ABSENCE of a branch, which a behavioural test can only sample. So also
    assert the branch is not there."""
    import ast
    import inspect

    import ax_server.analysis.rules as mod

    tree = ast.parse(inspect.getsource(mod))
    edge_fields = {"runtime_inbound_edges", "runtime_outbound_edges", "runtime_reachable"}

    def names(node):
        return {
            n.attr for n in ast.walk(node) if isinstance(n, ast.Attribute)
        } | {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}

    for node in ast.walk(tree):
        # No `not <edge field>` and no `<edge field> == 0 / < 1 / is False`.
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            assert not (names(node) & edge_fields), ast.unparse(node)
        if isinstance(node, ast.Compare) and (names(node.left) & edge_fields):
            op = node.ops[0]
            assert isinstance(op, (ast.Gt, ast.GtE)), ast.unparse(node)
            # `> 0` only. Anything else is reading absence.
            assert ast.unparse(node.comparators[0]) == "0", ast.unparse(node)
    # And the non-evidence reason can never be treated as a blocker.
    assert not (mod.NON_EVIDENCE_REASON_HEADS & mod.BLOCKING_REASON_HEADS)


def test_a_lossy_window_still_cannot_make_absence_mean_anything():
    """Every counter in `EdgeHealth` is a way an edge went missing. None of
    them changes the verdict, because absence was already worth zero."""
    outcome = evaluate(_facts(runtime_edges_reported=True, runtime_inbound_edges=0))
    assert outcome.status == DEAD_CANDIDATE
    assert "runtime-edge-absent" not in outcome.blockers


def test_one_observed_inbound_edge_makes_a_method_live_full_stop():
    outcome = evaluate(
        _facts(
            runtime_edges_reported=True,
            runtime_inbound_edges=1,
            runtime_inbound_observations=4,
            runtime_edges_sample_rate=1024,
        )
    )
    assert outcome.status == LIVE
    reason = next(r for r in outcome.reasons if r.startswith("runtime-edge-observed:"))
    assert "1-in-1024" in reason
    assert "4 sampled observation" in reason


def test_presence_beats_every_other_clause():
    """"Full stop" is literal: an observed edge wins over a suppression, a
    public-API surface, an unresolved static graph and a rate cap."""
    outcome = evaluate(
        _facts(
            runtime_inbound_edges=2,
            runtime_edges_reported=True,
            public_api=True,
            static_reachable=None,
            class_loaded=False,
            usable_windows=0,
            rate_limit_reason="cap reached",
        )
    )
    assert outcome.status == LIVE


def test_runtime_reachable_is_never_false():
    """The type says it: `True | None`, no `False`. A sampled graph cannot
    express "definitely not called", and a `False` would be read as if it
    could."""
    assert _facts(runtime_inbound_edges=0).runtime_reachable is None
    assert _facts(runtime_inbound_edges=3).runtime_reachable is True
    for edges in range(4):
        assert _facts(runtime_inbound_edges=edges).runtime_reachable is not False


def test_the_static_and_runtime_graphs_stay_separate_in_the_verdict(ingested_v3, engine):
    run = engine.derive(BUILD_SHA)
    v = verdict_map(run)

    pick = v[f"{RS}#{PICK}"]
    # Static says reachable (the gateway calls it in the bytecode); runtime
    # says reachable (the gateway really called it). Two fields, two answers,
    # never one merged boolean.
    assert pick.static_reachable is True
    assert pick.runtime_reachable is True

    # legacyFallback: the static graph looked and found no caller (False);
    # the runtime graph has nothing to say (None) and is not allowed to
    # pretend otherwise.
    dead = v[f"{RS}#legacyFallback()V"]
    assert dead.static_reachable is False
    assert dead.runtime_reachable is None
    assert dead.status == DEAD_CANDIDATE


def test_an_observed_edge_makes_an_unprobed_method_live(edge_collector, engine):
    """The case that only this tier can answer: MonthEndReport#render has no
    probe bit set in any window, and its only static caller is `unresolved`.
    An observed inbound edge settles it."""
    for day in range(4):
        edge_collector.ingest(
            realistic_payload_v3(
                day,
                edges=[
                    *DEFAULT_EDGES,
                    {"fromClass": RS, "fromIdx": 0, "toClass": MONTH_END, "toIdx": 0,
                     "count": 2},
                ],
            )
        )
    v = verdict_map(engine.derive(BUILD_SHA))
    render = v[f"{MONTH_END}#render()V"]
    assert render.status == LIVE
    assert render.runtime_reachable is True
    assert render.runtime_inbound_edges == 1
    assert render.runtime_inbound_observations == 8
    assert render.runtime_edges_sample_rate == 1024
    assert render.static_reachable is None  # still unresolved; not merged


def test_an_observed_edge_revokes_a_standing_dead_candidate(edge_collector, engine):
    """C53 through the new tier: the claim is re-derived and dies the moment
    an edge shows up."""
    for day in range(4):
        edge_collector.ingest(realistic_payload_v3(day))
    before = engine.reconcile(BUILD_SHA)
    assert [v.method for v in before.by_status(DEAD_CANDIDATE)] == ["legacyFallback"]

    edge_collector.ingest(
        realistic_payload_v3(
            4, edges=[{"fromClass": RS, "fromIdx": 0, "toClass": RS, "toIdx": 2, "count": 1}]
        )
    )
    after = engine.reconcile(BUILD_SHA)
    assert after.by_status(DEAD_CANDIDATE) == ()
    assert [ref.name for ref, _ in after.revoked] == ["legacyFallback"]
    assert "LIVE" in engine.ledger.get(BUILD_SHA, MethodRef(RS, "legacyFallback", "()V")).\
        revoked_reason


def test_verdict_json_reports_the_two_graphs_under_two_keys(ingested_v3, engine):
    row = verdict_map(engine.derive(BUILD_SHA))[f"{RS}#{NORMALISE}"].to_json()
    assert row["staticReachable"] is True
    assert row["runtimeReachable"] is True
    assert row["runtimeInboundEdges"] == 2
    assert row["runtimeInboundSampledObservations"] == (5 + 3) * 8
    assert row["edgesSampleRate"] == 1024
    assert row["runtimeEdgesReported"] is True


def test_an_empty_graph_is_reported_as_no_data_not_as_no_callers():
    graph = RuntimeCallGraph(build_sha=BUILD_SHA)
    assert graph.reported is False
    assert graph.has_inbound(RS, 0) is False
    assert graph.inbound_evidence(RS, 0).present is False
    assert "ABSENCE is not evidence" in graph.caveat()


# ======================================================================
# 4. api + mcp
# ======================================================================


@pytest.fixture
def api(ingested_v3, engine):
    return QueryService(engine, collector=ingested_v3)


def test_callers_of_returns_observed_callers_with_the_sample_rate(api):
    out = api.callers_of(BUILD_SHA, RS, "normalise")
    assert out["idx"] == 1
    assert {c["class"] for c in out["callers"]} == {RS}
    assert {c["method"] for c in out["callers"]} == {PICK, "cachedLookup(Ljava/lang/String;)Lcom/acme/Rate;"}
    top = out["callers"][0]
    # The count is named for what it is, and never appears without its rate.
    assert top["sampledObservations"] == 5 * 8
    assert top["edgesSampleRate"] == 1024
    assert top["estimatedCalls"] == 5 * 8 * 1024
    assert "order of magnitude" in top["estimateBasis"]
    assert out["edgesSampleRate"] == 1024
    assert "NOT calls" in out["countSemantics"]
    assert "ABSENCE is not evidence" in out["absenceNote"]


def test_no_api_response_presents_a_sampled_count_as_a_call_total(api):
    """Naming test. `calls` means calls elsewhere in this API (`hot_methods`
    returns real tier-2 call counters), so an edge response must never use it
    for a sampled observation count."""
    for out in (
        api.callers_of(BUILD_SHA, RS, "normalise"),
        api.callees_of(BUILD_SHA, RS, "pick"),
        api.hot_paths(BUILD_SHA),
    ):
        rows = out.get("callers") or out.get("callees") or out["paths"]
        for row in rows:
            assert "calls" not in row
            assert "count" not in row
            assert row["sampledObservations"] >= 0
            assert "edgesSampleRate" in row
            # An estimate is allowed, but only under a name that says so.
            assert "estimatedCalls" in row


def test_callees_of_is_the_other_direction(api):
    out = api.callees_of(BUILD_SHA, RS, "pick")
    assert [c["method"] for c in out["callees"]] == [NORMALISE]
    assert out["direction"] == "callees"


def test_an_ambiguous_method_name_raises_instead_of_guessing(api, engine):
    entry = engine.manifest.klass(RS)
    engine.manifest._by_name[RS] = type(entry)(
        name=entry.name,
        methods=(*entry.methods, MethodEntry(idx=4, name="pick", desc="(Ljava/lang/String;)V")),
    )
    with pytest.raises(ValueError) as exc:
        api.callers_of(BUILD_SHA, RS, "pick")
    assert "overloaded" in str(exc.value)
    assert "pick(Ljava/lang/String;)V" in str(exc.value)


def test_an_unknown_method_raises(api):
    with pytest.raises(ValueError):
        api.callers_of(BUILD_SHA, RS, "noSuchMethod")
    with pytest.raises(ValueError):
        api.callers_of(BUILD_SHA, "com.acme.Nope", "x")


def test_blast_radius_says_whether_an_observed_caller_is_itself_live(api):
    out = api.blast_radius(BUILD_SHA, RS, "normalise")
    runtime = out["runtime"]
    assert runtime["observedCallerCount"] == 2
    assert runtime["anyObservedCallerIsLive"] is True
    live = [c for c in runtime["observedCallers"] if c["isLive"]]
    assert [c["method"] for c in live] == [PICK]
    assert runtime["edgesSampleRate"] == 1024
    guidance = out["readBeforeDeleting"]
    assert "itself LIVE" in guidance
    assert "live code path reached this method" in guidance


def test_blast_radius_keeps_static_and_runtime_in_separate_sections(api):
    out = api.blast_radius(BUILD_SHA, RS, "normalise")
    assert "runtime" in out and "static" in out
    assert "61%" in out["static"]["graphSemantics"]
    # The static graph lists five callers for normalise (pick, legacyFallback,
    # cachedLookup, KillSwitch#trip, NeverLoaded#run); the runtime graph saw
    # two of them actually happen. Neither number corrects the other.
    assert out["static"]["callerCount"] == 5
    assert {c["method"] for c in out["static"]["callers"]} == {
        "pick", "legacyFallback", "cachedLookup", "trip", "run",
    }
    assert out["runtime"]["observedCallerCount"] == 2
    # Each section declares its own provenance, and the only key they share
    # is that declaration -- no caller list, count or flag is common to both,
    # and there is no merged "reachable" anywhere in the response.
    assert set(out["runtime"]) & set(out["static"]) == {"source"}
    assert out["runtime"]["source"] != out["static"]["source"]
    # No FIELD anywhere in either section is a merged reachability flag; the
    # verdict below keeps `staticReachable` and `runtimeReachable` apart, and
    # nothing here collapses them.
    keys = {k for section in ("runtime", "static") for k in out[section]}
    assert not any("reachable" in k.lower() for k in keys)
    assert {"staticReachable", "runtimeReachable"} <= set(out["verdict"])


def test_blast_radius_on_a_candidate_with_no_observed_callers_says_so(api):
    out = api.blast_radius(BUILD_SHA, RS, "legacyFallback")
    assert out["verdict"]["status"] == DEAD_CANDIDATE
    assert out["runtime"]["observedCallerCount"] == 0
    assert out["runtime"]["anyObservedCallerIsLive"] is False
    guidance = out["readBeforeDeleting"]
    assert "NOT evidence" in guidance
    assert "contributed nothing to that verdict" in guidance


def test_blast_radius_reports_a_tier_that_never_ran_as_a_configuration_fact(ingested, engine):
    api = QueryService(engine, collector=ingested)
    out = api.blast_radius(BUILD_SHA, RS, "legacyFallback")
    assert out["runtime"]["tierReported"] is False
    assert "never reported" in out["readBeforeDeleting"]
    assert "configuration fact" in out["readBeforeDeleting"]


def test_hot_paths_ranks_observed_edges(api):
    out = api.hot_paths(BUILD_SHA)
    assert [p["sampledObservations"] for p in out["paths"]] == [7 * 8, 5 * 8, 3 * 8]
    top = out["paths"][0]
    assert top["caller"] == {"class": GATEWAY, "idx": 0, "method": "dispatch()V"}
    assert top["callee"]["method"] == PICK
    assert out["edgesSampleRate"] == 1024
    assert "not call totals" in out["rankingNote"] or "not comparable" in out["rankingNote"]


def test_agent_health_surfaces_every_edge_loss_counter(api):
    tier = api.agent_health(BUILD_SHA)["edgeTier"]
    assert tier["windowsWithTierEnabled"] == 8
    assert tier["sampledRoots"] == 41 * 8
    assert tier["recorded"] == 15 * 8
    assert tier["truncatedDepth"] == 1 * 8
    assert tier["edgesSampleRates"] == {"1024": 8}
    assert "NOT `degraded`" in tier["note"]
    ingest = api.agent_health(BUILD_SHA)["ingest"]
    assert ingest["edgeRecordsMerged"] == 3 * 8
    assert ingest["edgeSampledObservations"] == 15 * 8
    assert ingest["edgesSampleRates"] == {"1024": 8}


def test_summary_reports_the_tier_and_its_caveat(api):
    edges = api.summary(BUILD_SHA)["runtimeEdges"]
    assert edges["reported"] is True and edges["tierArmed"] is True
    assert edges["methodsWithObservedInbound"] == 2
    assert edges["methodsWithObservedOutbound"] == 3
    assert "ABSENCE is not evidence" in edges["note"]


@pytest.fixture
def mcp(api):
    return McpServer(api)


def _tool(mcp, name, args=None):
    resp = mcp.handle(
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
         "params": {"name": name, "arguments": args or {}}}
    )
    assert "error" not in resp, resp
    return json.loads(resp["result"]["content"][0]["text"])


def test_mcp_exposes_the_four_edge_tools_as_thin_adapters(mcp, api):
    args = {"buildSha": BUILD_SHA, "class": RS, "method": "normalise"}
    assert _tool(mcp, "gt_callers_of", args) == json.loads(
        json.dumps(api.callers_of(BUILD_SHA, RS, "normalise"), default=str)
    )
    assert _tool(mcp, "gt_blast_radius", args)["runtime"]["anyObservedCallerIsLive"] is True
    assert _tool(mcp, "gt_callees_of", {**args, "method": "pick"})["direction"] == "callees"
    assert len(_tool(mcp, "gt_hot_paths", {"buildSha": BUILD_SHA})["paths"]) == 3


def test_mcp_tool_descriptions_carry_the_sampling_caveat(mcp):
    tools = {
        t["name"]: t
        for t in mcp.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})["result"]["tools"]
    }
    for name in ("gt_callers_of", "gt_callees_of", "gt_blast_radius", "gt_hot_paths"):
        text = tools[name]["description"]
        lower = text.lower()
        assert "sampl" in lower, name
        # Each tool must say, in its own description, either that the counts
        # are not calls or that absence is not evidence -- whichever of the
        # two mistakes that tool invites.
        assert ("not calls" in lower or "not call totals" in lower
                or "not evidence" in lower), name


def test_mcp_reports_an_ambiguous_or_unknown_method_as_an_error(mcp):
    resp = mcp.handle(
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
         "params": {"name": "gt_callers_of",
                    "arguments": {"buildSha": BUILD_SHA, "class": RS, "method": "nope"}}}
    )
    assert resp["error"]["code"] == -32000


# ======================================================================
# 5. HTTP
# ======================================================================


@pytest.fixture
def server(store, edge_collector, engine):
    import threading

    from ax_server.api.http import make_api_router
    from ax_server.collector.http import Router, make_collector_router, serve

    api = QueryService(engine, collector=edge_collector)
    router = (
        Router().extend(make_collector_router(edge_collector)).extend(make_api_router(api))
    )
    httpd = serve(router, port=0)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()
    thread.join(timeout=5)


def _get(base, path):
    import urllib.request

    with urllib.request.urlopen(base + path, timeout=10) as resp:
        return resp.status, json.loads(resp.read())


def test_http_ingest_v3_then_query_the_graph(server):
    import gzip
    import urllib.request

    for day in range(4):
        for pod in ("pod-a", "pod-b"):
            body = gzip.compress(
                json.dumps(realistic_payload_v3(day, instance_id=pod)).encode()
            )
            req = urllib.request.Request(
                server + "/v1/ingest",
                data=body,
                headers={"Content-Type": "application/json", "Content-Encoding": "gzip"},
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                assert resp.status == 202
                accepted = json.loads(resp.read())
                assert accepted["edgesMerged"] == 3
                assert accepted["edgesSampleRate"] == 1024

    status, callers = _get(
        server, f"/v1/builds/{BUILD_SHA}/callers?class={RS}&method=normalise"
    )
    assert status == 200
    assert callers["totalSampledObservations"] == (5 + 3) * 8
    assert callers["edgesSampleRate"] == 1024

    status, callees = _get(server, f"/v1/builds/{BUILD_SHA}/callees?class={RS}&method=pick")
    assert [c["method"] for c in callees["callees"]] == [NORMALISE]

    status, blast = _get(
        server, f"/v1/builds/{BUILD_SHA}/blast-radius?class={RS}&method=normalise"
    )
    assert blast["runtime"]["anyObservedCallerIsLive"] is True

    status, hot = _get(server, f"/v1/builds/{BUILD_SHA}/hot-paths?limit=2")
    assert [p["sampledObservations"] for p in hot["paths"]] == [7 * 8, 5 * 8]


def test_http_edge_routes_require_class_and_method(server):
    import urllib.error
    import urllib.request

    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(server + f"/v1/builds/{BUILD_SHA}/callers", timeout=10)
    assert exc.value.code == 400


def test_http_unknown_method_is_404(server):
    import urllib.error
    import urllib.request

    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(
            server + f"/v1/builds/{BUILD_SHA}/callers?class={RS}&method=nope", timeout=10
        )
    assert exc.value.code == 404


def test_http_rejects_a_dangling_edge_with_422(server):
    import urllib.error
    import urllib.request

    body = realistic_payload_v3(
        0, edges=[{"fromClass": RS, "fromIdx": 0, "toClass": RS, "toIdx": 99, "count": 1}]
    )
    req = urllib.request.Request(
        server + "/v1/ingest",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(req, timeout=10)
    assert exc.value.code == 422
    assert json.loads(exc.value.read())["error"] == RejectReason.UNKNOWN_EDGE_ENDPOINT


# ======================================================================
# 6. layering / port shape
# ======================================================================


def test_the_frozen_store_port_gained_no_abstract_methods():
    """CONTRACTS 3 lists eight methods. The edge tier is an OPTIONAL port
    extension, exactly like `WindowAttribution` and `IngestAudit`, so an
    adapter written against 3 still satisfies `Store`."""
    from ax_server.store.port import Store

    assert sorted(Store.__abstractmethods__) == [
        "class_loaded_ever",
        "coverage",
        "first_seen",
        "last_seen",
        "merge_coverage",
        "observed_windows",
        "record_window",
        "tier2_buckets",
    ]


def test_the_edge_port_is_feature_detected_not_required():
    from ax_server.store.port import EdgeStore, Store

    assert not issubclass(Store, EdgeStore)
    assert set(EdgeStore.__abstractmethods__) == {
        "record_edges",
        "callers_of",
        "callees_of",
        "hot_edges",
        "edge_endpoints",
        "edge_sample_rates",
    }


def test_edge_records_carry_the_manifest_identity_on_both_ends():
    rec = EdgeRecord(caller_cls="a.B", caller_idx=1, callee_cls="c.D", callee_idx=2, count=9)
    assert rec.caller == ("a.B", 1)
    assert rec.callee == ("c.D", 2)


def test_a_method_index_can_be_built_without_the_analysis_layer():
    """The collector's port must be satisfiable by anything, which is what
    keeps `collector` from importing `analysis`."""
    index = MethodIndex("sha", [("a.B", 0), ("a.B", 1)], source="hand-rolled")
    assert index.has_method("sha", "a.B", 1)
    assert not index.has_method("sha", "a.B", 2)
    assert "hand-rolled" in index.describe()


def test_a_two_pod_two_window_fixture_merges_exactly(store, manifest, suppressions, calendar):
    """The end-to-end arithmetic, spelled out: two windows, two pods, four
    reports, one graph."""
    collector = CollectorService(store, known_methods=manifest_method_index(manifest))
    for day in (0, 1):
        for pod in ("pod-a", "pod-b"):
            collector.ingest(realistic_payload_v3(day, instance_id=pod))
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions,
        ledger=SqliteProposalLedger(":memory:"),
    )
    try:
        api = QueryService(engine, collector=collector)
        callers = api.callers_of(BUILD_SHA, RS, "normalise")
        assert callers["totalSampledObservations"] == (5 + 3) * 4
        assert callers["edgesSampleRate"] == 1024
        callees = api.callees_of(BUILD_SHA, RS, "pick")
        assert callees["callees"][0]["sampledObservations"] == 5 * 4
        assert callees["callees"][0]["windows"] == 4
        assert sorted(set_bits(store.coverage(BUILD_SHA, RS))) == [0, 1]
        blast = api.blast_radius(BUILD_SHA, RS, "normalise")
        assert blast["runtime"]["anyObservedCallerIsLive"] is True
    finally:
        engine.close()


def test_edges_recorded_without_a_window_still_aggregate(store):
    """Defensive: the aggregate is keyed on the build, not on a window row."""
    from ax_server.store.models import AgentHealth, EdgeHealth, IngestWindow

    store.record_edges(
        IngestWindow(
            schema_version=1,
            build_sha=BUILD_SHA,
            artifact="checkout-service",
            instance_id="pod-x",
            window_start_ms=0,
            window_end_ms=1000,
            agent_health=AgentHealth(edges=EdgeHealth(enabled=True, sample_rate=64)),
            edges=(EdgeRecord("a.B", 0, "c.D", 1, 3),),
            edges_present=True,
        )
    )
    assert store.callers_of(BUILD_SHA, "c.D", 1)[0].sampled_observations == 3
    assert store.window_edges(BUILD_SHA)[0]["windowId"] == 0


def test_window_edges_audit_trail_keeps_the_rate_per_window(edge_collector):
    _window_with_edges(edge_collector, day=0)
    rows = edge_collector.store.window_edges(BUILD_SHA)
    assert len(rows) == 3
    assert all(r["edgesSampleRate"] == 1024 for r in rows)
    assert all(r["windowId"] > 0 for r in rows)


def test_a_window_with_no_edges_key_is_distinguishable_from_an_empty_one(edge_collector):
    absent = edge_collector.ingest(realistic_payload(0))
    empty = edge_collector.ingest(realistic_payload_v3(1, edges=[]))
    assert absent.edges_reported is False
    assert empty.edges_reported is True and empty.edges_merged == 0
    snap = edge_collector.health.snapshot()
    assert snap["edgeWindowsReporting"] == 1
    assert snap["edgeWindowsTierEnabled"] == 1


def test_the_edge_payload_shape_is_the_contract_example(edge_collector):
    """CONTRACTS 2 v3 prints one `edges[]` entry; ingest it verbatim."""
    body = payload(
        start=DAY0,
        end=DAY0 + timedelta(hours=1),
        classes_loaded=[RS, GATEWAY],
        coverage=[{"class": RS, "schemaHash": "5f2a0001", "probes": probes(0)}],
        edges=[
            {
                "fromClass": "com.acme.api.PublicGateway",
                "fromIdx": 0,
                "toClass": "com.acme.shipping.RateSelector",
                "toIdx": 0,
                "count": 17,
            }
        ],
    )
    result = edge_collector.ingest(body)
    assert result.accepted
    assert result.edges_merged == 1
    assert result.edge_sampled_observations == 17
    assert result.edges_sample_rate == 1024
    assert result.edge_endpoints_verified is True
