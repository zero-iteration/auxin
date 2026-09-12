"""Manifest parsing, and the safe-direction defaults for post-freeze fields."""

import pytest

from ax_server.analysis.manifest import ManifestError, load_manifest
from ax_server.analysis.models import DEAD_CANDIDATE, UNKNOWN, EligibilityClass, MethodRef

from conftest import BUILD_SHA, verdict_map

RS = "com.acme.shipping.RateSelector"


def test_parses_the_contract_1_shape(manifest):
    assert manifest.build_sha == "abc123def"
    assert manifest.artifact == "checkout-service"
    rs = manifest.klass(RS)
    assert rs is not None
    assert rs.probe_count == 4
    assert rs.source_file == "RateSelector.java"
    assert [m.idx for m in rs.methods] == [0, 1, 2, 3]


def test_unresolved_is_a_first_class_resolution(manifest):
    resolutions = {e.resolution for e in manifest.call_edges}
    assert "unresolved" in resolutions and "exact" in resolutions


def test_an_invalid_resolution_is_rejected():
    with pytest.raises(ManifestError):
        load_manifest(
            {"classes": [], "callEdges": [{"from": "A#a()V", "to": "B#b()V",
                                           "resolution": "probably"}]}
        )


def test_missing_idx_is_rejected():
    # A14 defect 2: an index that is not build-time assigned silently
    # misattributes coverage. We refuse the manifest instead.
    with pytest.raises(ManifestError):
        load_manifest({"classes": [{"name": "A", "methods": [{"name": "m", "desc": "()V"}]}]})


def test_absent_dynamicallyObservable_defaults_to_not_observable():
    m = load_manifest(
        {"classes": [{"name": "A", "methods": [{"idx": 0, "name": "m", "desc": "()V"}]}]}
    )
    method = m.method(MethodRef("A", "m", "()V"))
    assert method.eligibility is EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE


def test_absent_shortCircuitable_defaults_to_true():
    m = load_manifest(
        {"classes": [{"name": "A", "methods": [{"idx": 0, "name": "m", "desc": "()V"}]}]}
    )
    assert m.method(MethodRef("A", "m", "()V")).short_circuitable is True


def test_observability_string_wins_over_the_bool():
    m = load_manifest(
        {"classes": [{"name": "A", "methods": [
            {"idx": 0, "name": "m", "desc": "()V",
             "observability": "de-instrumented", "dynamicallyObservable": True}
        ]}]}
    )
    assert m.method(MethodRef("A", "m", "()V")).eligibility is EligibilityClass.DE_INSTRUMENTED


def test_a_contract_1_literal_manifest_nominates_nothing(store, suppressions, calendar):
    """A manifest without the post-freeze flags is telling us ax-static has not
    been upgraded. The safe reading is that we cannot nominate anything."""
    from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
    from ax_server.analysis.proposals import SqliteProposalLedger

    manifest = load_manifest(
        {
            "buildSha": BUILD_SHA,
            "artifact": "svc",
            "classes": [{"name": "A", "schemaHash": "h", "methods": [
                {"idx": 0, "name": "m", "desc": "()V", "access": "private"}
            ]}],
            "entryPoints": [],
            "callEdges": [{"from": "A#m()V", "to": "B#b()V", "resolution": "exact"}],
        }
    )
    engine = AnalysisEngine(
        store, manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions, ledger=SqliteProposalLedger(":memory:"),
    )
    run = engine.derive(BUILD_SHA)
    assert run.by_status(DEAD_CANDIDATE) == ()
    engine.close()


def test_short_circuitable_method_with_every_other_clause_passing_is_unknown(
    ingested, engine
):
    """THE guard on the sharpest false-positive vector we found.

    `cachedLookup` is @Cacheable-shaped: zero observations, static-unreachable,
    unsuppressed, not public API, class loaded, window fully phase-covered.
    Every other clause passes. It must still be UNKNOWN.
    """
    run = engine.derive(BUILD_SHA)
    v = verdict_map(run)[f"{RS}#cachedLookup(Ljava/lang/String;)Lcom/acme/Rate;"]

    assert v.status == UNKNOWN
    assert v.status != DEAD_CANDIDATE
    # ...and every other clause really did pass:
    assert v.static_reachable is False
    assert v.suppressed is False
    assert v.public_api is False
    assert v.eligibility is EligibilityClass.OBSERVABLE
    assert v.phases_missing == []
    assert v.last_seen is None
    assert any(r.startswith("runtime-unobserved") for r in v.reasons)
    assert any(r.startswith("class-loaded") for r in v.reasons)
    assert any(r.startswith("usable-windows") for r in v.reasons)
    # The ONLY thing standing between it and a nomination:
    assert any(r.startswith("short-circuitable:") for r in v.reasons)

    # The sibling with the same profile but shortCircuitable=false IS nominated,
    # which proves the fixture is not passing for some unrelated reason.
    sibling = verdict_map(run)[f"{RS}#legacyFallback()V"]
    assert sibling.status == DEAD_CANDIDATE
