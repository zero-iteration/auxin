"""Bug #18: `coverage[].probesInstalled`, end to end.

The probe array is sized to the manifest's `probeCount` -- every
probe-eligible method -- while the emitter installs a probe at only *some* of
those indices. An index with no probe is never written by anything, so its bit
is permanently zero, and it was being shipped in `coverage[].probes` alongside
genuinely-zero bits: as evidence that a method never ran. A window from a JVM
with `ax.tier1.enabled=false` therefore looked exactly like "every method in
your codebase is dead".

Every test here guards one clause of the reading rule::

    probes                        -> liveness (a set bit means it ran)
    probesInstalled & ~probes     -> the ONLY death evidence
    ~probesInstalled              -> SILENCE. not evidence of anything.

and the three-way distinction an operator has to be able to make:

    manifest says it cannot be covered      -> NOT_DYNAMICALLY_OBSERVABLE (C51)
    our own tier-1b stripped the probe      -> DE_INSTRUMENTED (C4)
    observable, but nothing ever instrumented it -> NO_PROBE_INSTALLED (#18)
"""

import ast
import inspect
import itertools

import pytest

from ax_server.analysis.instrumentation import InstalledProbes, load_installed_probes
from ax_server.analysis.manifest import ClassEntry, MethodEntry
from ax_server.analysis.models import (
    DEAD_CANDIDATE,
    LIVE,
    NOT_DYNAMICALLY_OBSERVABLE,
    UNKNOWN,
    EligibilityClass,
    MethodRef,
    effective_eligibility,
)
from ax_server.analysis.phases import PhaseCoverage
from ax_server.analysis.rules import BLOCKING_REASON_HEADS, MethodFacts, evaluate
from ax_server.api.service import QueryService
from ax_server.collector.errors import IngestRejected
from ax_server.collector.health import RejectReason
from ax_server.mcp.server import McpServer
from ax_server.store.bitset import and_not, from_indices, popcount, set_bits
from ax_server.store.errors import SchemaMismatch

from conftest import (
    BUILD_SHA,
    FULLY_INSTALLED,
    TIER1_DISABLED,
    installed_mask,
    probes,
    realistic_payload,
    realistic_payload_installed,
    verdict_map,
)

RS = "com.acme.shipping.RateSelector"
LEGACY = f"{RS}#legacyFallback()V"
PICK = f"{RS}#pick(Ljava/util/List;)Lcom/acme/Rate;"
NORMALISE = f"{RS}#normalise(Lcom/acme/Rate;)Lcom/acme/Rate;"
CACHED = f"{RS}#cachedLookup(Ljava/lang/String;)Lcom/acme/Rate;"
CONSTANTS = "com.acme.util.Constants"


def _with_rate_selector(indices):
    """FULLY_INSTALLED, with one class's installed set replaced."""
    masks = dict(FULLY_INSTALLED)
    masks[RS] = list(indices)
    return masks


# ======================================================================
# 1. the bitset primitive
# ======================================================================


def test_and_not_is_the_only_expression_that_yields_death_evidence():
    installed = from_indices([0, 1, 2, 3])
    ran = from_indices([0, 1])
    assert sorted(set_bits(and_not(installed, ran))) == [2, 3]


def test_and_not_never_invents_an_index_past_either_operand():
    # An index past the end of `a` is not in `a`; one past the end of `b` is
    # clear there. Widening in either direction would manufacture evidence.
    assert and_not(b"", from_indices([9])) == b""
    assert sorted(set_bits(and_not(from_indices([9]), b""))) == [9]


def test_an_all_zero_install_mask_yields_no_death_evidence_at_all():
    """The tier-1-disabled shape, at the arithmetic level: nothing ran, and
    nothing could have been seen running, so there is no evidence."""
    assert popcount(and_not(from_indices([], size_bytes=1), b"")) == 0


# ======================================================================
# 2. collector: decode, tolerate, count
# ======================================================================


def test_the_install_mask_is_decoded_and_persisted(collector):
    result = collector.ingest(realistic_payload_installed(0))
    assert result.accepted
    assert result.install_masks_merged == 6
    assert result.records_without_install_mask == 0
    assert sorted(set_bits(collector.store.probes_installed(BUILD_SHA, RS))) == [0, 1, 2, 3]
    # C51 skips are visible in the mask too: the emitter installed nothing at
    # Constants#maxRetries, which the manifest already explains.
    assert sorted(set_bits(collector.store.probes_installed(BUILD_SHA, CONSTANTS))) == [1]


def test_an_absent_mask_is_tolerated_and_is_not_an_all_zero_one(collector):
    """A pre-#18 agent. `None` must survive all the way down: normalising it
    to an all-zero mask here would withdraw every index in the build from the
    death argument on the strength of a producer's age."""
    result = collector.ingest(realistic_payload(0))
    assert result.accepted
    assert result.install_masks_merged == 0
    assert result.records_without_install_mask == 6
    assert collector.store.probes_installed(BUILD_SHA, RS) is None


def test_an_all_zero_mask_is_a_report_and_an_absent_one_is_silence(collector):
    collector.ingest(
        realistic_payload_installed(0, installed=TIER1_DISABLED, probes_set={})
    )
    mask = collector.store.probes_installed(BUILD_SHA, RS)
    # Not None -- a window really said "I installed nothing here" -- and empty.
    assert mask is not None
    assert popcount(mask) == 0


def test_an_unreadable_install_mask_is_refused_not_guessed(collector):
    """Guessing "all installed" re-creates the bug; guessing "none installed"
    silently discards every candidate in the window without saying so."""
    body = realistic_payload_installed(0)
    body["coverage"][0]["probesInstalled"] = "!!!not base64!!!"
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.BAD_PROBE_BITSET
    assert "probesInstalled" in exc.value.detail


def test_strip_mask_missing_is_persisted_with_the_other_counters(collector):
    result = collector.ingest(realistic_payload_installed(0, strip_mask_missing=3))
    assert result.strip_mask_missing == 3
    window = collector.store.observed_windows(BUILD_SHA)[0]
    assert window.agent_health.strip_mask_missing == 3
    # And NOT folded into `degraded`: the coverage bits in this window are
    # still perfectly good evidence of LIFE.
    assert window.degraded is False
    assert window.usable_as_death_evidence is True
    snap = collector.health.snapshot()
    assert snap["stripMaskMissingTotal"] == 3
    assert snap["stripMaskMissingWindows"] == 1
    assert snap["probeInstallMaskRecords"] == 6


def test_a_boolean_strip_mask_missing_is_folded_not_refused(collector):
    """An agent that reports the condition as a flag is still telling us
    something we must not throw away -- and refusing the window would discard
    its coverage bits with it."""
    result = collector.ingest(realistic_payload_installed(0, strip_mask_missing=True))
    assert result.strip_mask_missing == 1


def test_a_negative_strip_mask_missing_is_refused(collector):
    body = realistic_payload_installed(0)
    body["agentHealth"]["stripMaskMissing"] = -1
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.BAD_FIELD


def test_a_probe_set_at_an_uninstalled_index_is_counted_loudly(collector, caplog):
    """A probe that fired must have been installed, so the mask is stale or
    wrong. Counted, never fatal: the SET BIT is still trustworthy evidence of
    life, and the mask is only ever used to withhold death evidence."""
    with caplog.at_level("WARNING"):
        result = collector.ingest(
            realistic_payload_installed(0, installed=TIER1_DISABLED)
        )
    assert result.accepted
    assert result.install_mask_inconsistencies == 1
    assert "INSTALL MASK INCONSISTENT" in caplog.text
    snap = collector.health.snapshot()
    assert snap["rejectsByReason"][RejectReason.PROBE_INSTALL_MASK_INCONSISTENT] == 1
    # The liveness bits were merged anyway.
    assert sorted(set_bits(collector.store.coverage(BUILD_SHA, RS))) == [0, 1]


def test_a_store_without_the_port_says_so_instead_of_silently_dropping(caplog):
    """The gate not running is a fact worth logging. Nothing is misattributed
    -- the analysis falls back to "no mask reported" -- but an operator must
    be able to find out why."""
    from ax_server.collector.service import CollectorService
    from ax_server.store.port import ProbeInstallStore

    class MaskBlindStore:
        """Only the frozen CONTRACTS 3 surface, plus what ingest touches."""

        def __init__(self):
            self.coverage_bits = {}

        def record_window(self, w):
            return None

        def merge_coverage(self, build_sha, cls, schema_hash, probes_):
            self.coverage_bits[cls] = probes_

        def coverage(self, build_sha, cls):
            return self.coverage_bits.get(cls)

    store = MaskBlindStore()
    assert not isinstance(store, ProbeInstallStore)
    collector = CollectorService(store)  # type: ignore[arg-type]
    with caplog.at_level("WARNING"):
        result = collector.ingest(realistic_payload_installed(0))
    assert result.accepted
    assert result.install_masks_merged == 0
    assert "does not implement ProbeInstallStore" in caplog.text
    assert (
        collector.health.snapshot()["rejectsByReason"][
            RejectReason.PROBE_INSTALL_MASK_DISCARDED
        ]
        == 1
    )


# ======================================================================
# 3. store: OR across pods and windows, exactly as coverage merges
# ======================================================================


def test_the_mask_or_merges_across_pods_that_installed_different_subsets(store):
    """Different JVMs legitimately install different probe sets -- a
    class-file-<55 fallback on one node, a `frameEmissionUnsupported` method
    on another. The UNION is the conservative answer to the only question the
    mask is asked: could SOME JVM have observed this index?"""
    store.merge_probes_installed(BUILD_SHA, RS, "5f2a0001", from_indices([0, 1, 2]))
    store.merge_probes_installed(BUILD_SHA, RS, "5f2a0001", from_indices([0, 3]))
    assert sorted(set_bits(store.probes_installed(BUILD_SHA, RS))) == [0, 1, 2, 3]


def test_the_mask_merge_is_idempotent_like_coverage_and_unlike_edges(store):
    mask = from_indices([0, 2])
    for _ in range(3):
        store.merge_probes_installed(BUILD_SHA, RS, "h1", mask)
    assert sorted(set_bits(store.probes_installed(BUILD_SHA, RS))) == [0, 2]
    assert store.install_mask_windows(BUILD_SHA, RS) == 3


def test_a_shorter_mask_never_truncates_a_stored_one(store):
    store.merge_probes_installed(BUILD_SHA, RS, "h1", from_indices([17]))
    store.merge_probes_installed(BUILD_SHA, RS, "h1", from_indices([0]))
    assert sorted(set_bits(store.probes_installed(BUILD_SHA, RS))) == [0, 17]


def test_no_row_is_none_and_never_an_all_zero_mask(store):
    assert store.probes_installed(BUILD_SHA, RS) is None
    store.merge_probes_installed(BUILD_SHA, RS, "h1", b"\x00")
    # A report of "nothing installed" is NOT the absence of a report, even
    # though the payload is falsy.
    assert store.probes_installed(BUILD_SHA, RS) == b"\x00"
    assert store.installed_masks(BUILD_SHA) == {RS: b"\x00"}


def test_a_schema_hash_mismatch_refuses_the_mask_and_writes_nothing(store):
    """The mask is keyed by build-time probe index, so merging across a schema
    change would point the gate at other methods."""
    store.merge_probes_installed(BUILD_SHA, RS, "h1", from_indices([0]))
    with pytest.raises(SchemaMismatch):
        store.merge_probes_installed(BUILD_SHA, RS, "DIFFERENT", from_indices([1, 2]))
    assert sorted(set_bits(store.probes_installed(BUILD_SHA, RS))) == [0]


def test_the_frozen_store_port_gained_no_abstract_methods_for_this_either():
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


def test_the_install_mask_port_is_feature_detected_not_required():
    from ax_server.store.port import ProbeInstallStore, Store

    assert not issubclass(Store, ProbeInstallStore)
    assert set(ProbeInstallStore.__abstractmethods__) == {
        "merge_probes_installed",
        "probes_installed",
        "installed_masks",
    }


def test_an_adapter_without_the_port_reports_unknown_not_uninstalled(store):
    class Bare:
        pass

    probes_view = load_installed_probes(Bare(), BUILD_SHA)  # type: ignore[arg-type]
    assert probes_view.supported is False
    assert probes_view.reported is False
    assert probes_view.installed(RS, 0) is None
    assert "did not run" in probes_view.caveat()


# ======================================================================
# 4. the tri-state read
# ======================================================================


def test_installed_is_a_tri_state_and_none_means_no_mask_was_reported():
    view = InstalledProbes(
        build_sha=BUILD_SHA, masks={RS: from_indices([0])}, supported=True
    )
    assert view.installed(RS, 0) is True
    assert view.installed(RS, 1) is False
    # A class nobody reported: NOT False.
    assert view.installed("com.acme.Other", 0) is None
    assert view.reported_for(RS) is True
    assert view.reported_for("com.acme.Other") is False


def test_death_evidence_indices_are_exactly_installed_and_unset():
    view = InstalledProbes(
        build_sha=BUILD_SHA, masks={RS: from_indices([0, 1, 2])}, supported=True
    )
    assert view.death_evidence_indices(RS, from_indices([0])) == [1, 2]
    assert view.death_evidence_indices("com.acme.Other", b"") is None


def test_effective_eligibility_keeps_the_three_cases_apart():
    # The manifest already explains it (C51) -> unchanged, whatever the mask.
    for mask in (True, False, None):
        assert (
            effective_eligibility(EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE, mask)
            is EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE
        )
        assert (
            effective_eligibility(EligibilityClass.DE_INSTRUMENTED, mask)
            is EligibilityClass.DE_INSTRUMENTED
        )
    # Observable + nothing installed -> the NEW case.
    assert (
        effective_eligibility(EligibilityClass.OBSERVABLE, False)
        is EligibilityClass.NO_PROBE_INSTALLED
    )
    # Installed, or no mask at all -> unchanged. `None` must not reclassify.
    assert effective_eligibility(EligibilityClass.OBSERVABLE, True) is (
        EligibilityClass.OBSERVABLE
    )
    assert effective_eligibility(EligibilityClass.OBSERVABLE, None) is (
        EligibilityClass.OBSERVABLE
    )


# ======================================================================
# 5. the rule
# ======================================================================

COMPLETE = PhaseCoverage(covered=("month-end",), missing=(), window_days=90, span=None)
INCOMPLETE = PhaseCoverage(
    covered=("month-end",), missing=("year-end-close",), window_days=90, span=None
)


def facts(**over):
    """A method that is a DEAD_CANDIDATE unless a clause is flipped."""
    method = MethodEntry(
        idx=2,
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
        phase_coverage=COMPLETE,
        usable_windows=4,
        excluded_windows=0,
    )
    base.update(over)
    return MethodFacts(**base)


def test_the_baseline_is_still_a_candidate_when_no_mask_was_reported():
    """`probe_installed` defaults to None -- a pre-#18 build -- and that must
    change NOTHING, or closing this bug would silently empty every existing
    deployment's candidate list."""
    outcome = evaluate(facts())
    assert outcome.status == DEAD_CANDIDATE
    assert outcome.blockers == ()
    assert not any(r.startswith("probe-installed") for r in outcome.reasons)
    assert not any(r.startswith("no-probe-installed") for r in outcome.reasons)


def test_an_uninstalled_probe_can_never_be_a_dead_candidate():
    """THE BUG. Everything else about this method argues death; the only
    reason its bit is zero is that nothing was ever able to write it."""
    outcome = evaluate(facts(probe_installed=False))
    assert outcome.status == UNKNOWN
    assert "no-probe-installed" in outcome.blockers
    reason = next(r for r in outcome.reasons if r.startswith("no-probe-installed:"))
    assert "index 2" in reason
    assert "ax.tier1.enabled=false" in reason
    assert "not because the method never ran" in reason


def test_an_installed_probe_nominates_normally_and_says_so():
    outcome = evaluate(facts(probe_installed=True))
    assert outcome.status == DEAD_CANDIDATE
    assert any(r.startswith("probe-installed:") for r in outcome.reasons)


def test_no_probe_installed_is_a_blocking_reason_head():
    assert "no-probe-installed" in BLOCKING_REASON_HEADS


def test_the_three_instrumentation_cases_are_distinguishable_in_the_rule():
    c51 = evaluate(facts(eligibility=EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE))
    stripped = evaluate(facts(eligibility=EligibilityClass.DE_INSTRUMENTED))
    uninstalled = evaluate(facts(probe_installed=False))

    assert c51.status == NOT_DYNAMICALLY_OBSERVABLE
    assert stripped.status == UNKNOWN
    assert uninstalled.status == UNKNOWN
    assert [
        next(r.split(":")[0] for r in o.reasons if r.split(":")[0] in BLOCKING_REASON_HEADS)
        for o in (c51, stripped, uninstalled)
    ] == ["not-dynamically-observable", "de-instrumented", "no-probe-installed"]


def test_a_manifest_explained_skip_does_not_also_claim_no_probe_installed():
    """C51 and #18 are different findings and must not be double-reported: the
    manifest already explained this one, so only its own clause fires."""
    outcome = evaluate(
        facts(eligibility=EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE, probe_installed=False)
    )
    assert outcome.status == NOT_DYNAMICALLY_OBSERVABLE
    assert "no-probe-installed" not in outcome.blockers


def test_a_set_probe_still_wins_even_when_the_mask_disagrees():
    """Positive evidence of life beats everything. A stale mask cannot
    un-observe an observation -- the inconsistency is counted at ingest."""
    outcome = evaluate(facts(observed=True, probe_installed=False))
    assert outcome.status == LIVE


def test_an_uninstalled_probe_never_nominates_under_any_combination():
    """Exhaustive over the clause grid. `probe_installed is False` must never
    produce DEAD_CANDIDATE, whatever else is true."""
    options = [
        ("static_reachable", (False, True, None)),
        ("class_loaded", (True, False)),
        ("public_api", (False, True)),
        ("short_circuitable", (False, True)),
        ("usable_windows", (4, 0)),
        ("phase_coverage", (COMPLETE, INCOMPLETE)),
    ]
    keys = [name for name, _ in options]
    for combo in itertools.product(*(values for _, values in options)):
        flip = dict(zip(keys, combo, strict=True))
        assert evaluate(facts(probe_installed=False, **flip)).status != DEAD_CANDIDATE, flip


def test_no_mask_reported_is_indistinguishable_from_the_pre_18_rule():
    """`None` ABSTAINS: for every combination, a fact set with no mask gives
    the same status and the same blockers as one from before the field
    existed."""
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
        silent = evaluate(facts(**flip))
        explicit_none = evaluate(facts(probe_installed=None, **flip))
        assert silent.status == explicit_none.status, flip
        assert silent.blockers == explicit_none.blockers, flip


# ======================================================================
# 6. structural guards -- the enforcement is the ABSENCE of a branch
# ======================================================================


def _names(node):
    return {n.attr for n in ast.walk(node) if isinstance(n, ast.Attribute)} | {
        n.id for n in ast.walk(node) if isinstance(n, ast.Name)
    }


@pytest.mark.parametrize(
    "module_name", ["ax_server.analysis.rules", "ax_server.analysis.models"]
)
def test_the_install_mask_is_never_read_loosely(module_name):
    """Structural guard, the analogue of the edge tier's.

    `probe_installed` is a TRI-STATE and the third state is silence, so every
    read of it must be an explicit `is True` / `is False` / `is None`. A
    truthiness or `not` read collapses two of the three states into one:
    "no mask reported" -> "no probe installed" silently empties a pre-#18
    build's candidate list, and "no probe installed" -> "nothing is known" is
    bug #18 itself.
    """
    import importlib

    mod = importlib.import_module(module_name)
    tree = ast.parse(inspect.getsource(mod))
    field = "probe_installed"

    direct = (field, f"f.{field}", f"self.{field}")

    reads = 0
    for node in ast.walk(tree):
        # No `not <mask>` and no `<mask> and ...` / `if <mask>:` anywhere.
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            assert field not in _names(node), ast.unparse(node)
        if isinstance(node, ast.BoolOp):
            for value in node.values:
                assert ast.unparse(value) not in direct, ast.unparse(node)
        if isinstance(node, (ast.If, ast.IfExp, ast.While)):
            assert ast.unparse(node.test) not in direct, ast.unparse(node.test)
        # A DIRECT read must be `is True` / `is False` / `is None`. Handing the
        # field to the one shared function that performs the tri-state read is
        # a pass-through, not a read, and is deliberately allowed.
        if isinstance(node, ast.Compare) and ast.unparse(node.left) in direct:
            reads += 1
            assert all(isinstance(op, ast.Is) for op in node.ops), ast.unparse(node)
            for comparator in node.comparators:
                assert ast.unparse(comparator) in ("True", "False", "None"), ast.unparse(node)
    assert reads, f"{module_name} no longer reads {field} at all"


def test_dead_candidate_is_reachable_only_after_the_blocker_gate():
    """Structural guard on the shape of the decision.

    `evaluate` has exactly ONE DEAD_CANDIDATE return and an
    `if blockers: return UNKNOWN` stands before it. That is what makes every
    blocking clause -- including `no-probe-installed` -- structurally unable
    to leak into a nomination, rather than relying on a behavioural test to
    notice.
    """
    import ax_server.analysis.rules as mod

    tree = ast.parse(inspect.getsource(mod))
    fn = next(
        n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "evaluate"
    )
    gates = [
        i
        for i, stmt in enumerate(fn.body)
        if isinstance(stmt, ast.If) and ast.unparse(stmt.test) == "blockers"
    ]
    assert len(gates) == 1
    assert "UNKNOWN" in ast.unparse(fn.body[gates[0]])

    dead_returns = [
        n
        for n in ast.walk(fn)
        if isinstance(n, ast.Return) and "DEAD_CANDIDATE" in ast.unparse(n)
    ]
    assert len(dead_returns) == 1
    top_level_dead = [
        i
        for i, stmt in enumerate(fn.body)
        if isinstance(stmt, ast.Return) and "DEAD_CANDIDATE" in ast.unparse(stmt)
    ]
    assert top_level_dead == [len(fn.body) - 1]
    assert gates[0] < top_level_dead[0]


def test_liveness_is_only_ever_read_from_the_coverage_bitset():
    """Structural guard: the install mask must never be mistaken for the
    liveness bitset. `probes` says what RAN; the mask says what COULD have
    been seen running, and reading one as the other in either direction
    fabricates an answer."""
    import ax_server.analysis.engine as mod

    tree = ast.parse(inspect.getsource(mod))
    calls = [
        n
        for n in ast.walk(tree)
        if isinstance(n, ast.Call) and getattr(n.func, "id", "") == "is_set"
    ]
    assert calls, "the engine no longer reads liveness at all"
    for call in calls:
        assert ast.unparse(call.args[0]) == "probes", ast.unparse(call)

    for node in ast.walk(tree):
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            assert "installed" not in _names(node), ast.unparse(node)
        if isinstance(node, ast.Compare) and ast.unparse(node.left) == "installed":
            assert all(isinstance(op, ast.Is) for op in node.ops), ast.unparse(node)


# ======================================================================
# 7. end to end: the bug, and the fix
# ======================================================================


def test_a_tier1_disabled_window_yields_zero_dead_candidates(
    ingested_tier1_disabled, engine
):
    """THE BUG, end to end.

    Four production windows from a JVM with `ax.tier1.enabled=false`: every
    coverage bitset all-zero, every install mask all-zero, every method still
    `dynamicallyObservable: true`. This used to read as "every method in your
    codebase is dead".
    """
    run = engine.derive(BUILD_SHA)
    assert run.by_status(DEAD_CANDIDATE) == ()

    v = verdict_map(run)[LEGACY]
    assert v.status == UNKNOWN
    assert v.eligibility is EligibilityClass.NO_PROBE_INSTALLED
    assert v.probe_installed is False
    assert v.probe_install_mask_reported is True
    assert any(r.startswith("no-probe-installed:") for r in v.reasons)
    # Even the method that really did run reports nothing, because nothing
    # could have recorded it.
    assert verdict_map(run)[PICK].status == UNKNOWN


def test_the_same_windows_with_a_full_mask_nominate_normally(ingested_installed, engine):
    """The control: identical windows, the mask now says every index really
    was instrumented, and the candidate comes back."""
    run = engine.derive(BUILD_SHA)
    dead = run.by_status(DEAD_CANDIDATE)
    assert [(v.cls, v.method) for v in dead] == [(RS, "legacyFallback")]
    v = dead[0]
    assert v.eligibility is EligibilityClass.OBSERVABLE
    assert v.probe_installed is True
    assert any(r.startswith("probe-installed:") for r in v.reasons)
    assert verdict_map(run)[PICK].status == LIVE


def test_an_all_zero_probes_with_a_full_mask_is_real_death_evidence(collector, engine):
    """`probesInstalled & ~probes` is the only death evidence there is -- and
    when the mask is full and nothing ran, it is at its strongest."""
    for day in range(4):
        collector.ingest(realistic_payload_installed(day, probes_set={}))
    run = engine.derive(BUILD_SHA)
    v = verdict_map(run)
    assert v[LEGACY].status == DEAD_CANDIDATE
    assert v[LEGACY].probe_installed is True
    assert any(r.startswith("probe-installed:") for r in v[LEGACY].reasons)
    # ...and every OTHER clause stays in force. These are not blocked by the
    # mask -- they are blocked by static reachability and short-circuitability,
    # and closing #18 must not turn those off.
    assert v[NORMALISE].status == UNKNOWN
    assert any(r.startswith("static-reachable:") for r in v[NORMALISE].reasons)
    assert v[CACHED].status == UNKNOWN
    assert [f"{x.cls}#{x.method}" for x in run.by_status(DEAD_CANDIDATE)] == [
        f"{RS}#legacyFallback"
    ]


def test_an_older_payload_without_the_mask_neither_crashes_nor_invents(ingested, engine):
    """A pre-#18 agent: no `probesInstalled` anywhere. The gate abstains, the
    build keeps exactly the verdicts it had, and nothing new is nominated."""
    run = engine.derive(BUILD_SHA)
    assert len(run.by_status(DEAD_CANDIDATE)) == 1
    v = verdict_map(run)[LEGACY]
    assert v.status == DEAD_CANDIDATE
    assert v.probe_installed is None
    assert v.probe_install_mask_reported is False
    assert run.evidence.instrumentation.reported is False
    assert run.summary()["instrumentation"]["observableButNeverInstrumented"] == 0


def test_the_mask_is_never_mistaken_for_liveness(collector, engine):
    """A fully-set install mask with an all-zero coverage bitset must produce
    no LIVE verdict at all: the mask says what COULD have been observed, never
    what was."""
    for day in range(4):
        collector.ingest(realistic_payload_installed(day, probes_set={}))
    run = engine.derive(BUILD_SHA)
    assert run.by_status(LIVE) == ()


def test_two_pods_installing_different_subsets_union_into_the_answer(collector, engine):
    """The OR-merge, where it changes a verdict.

    pod-b never instrumented `legacyFallback` (idx 2), so on its own there is
    no evidence about it. pod-a did. The union -- "some JVM could have
    observed this index" -- is the conservative answer and restores the
    candidate.
    """
    for day in range(4):
        collector.ingest(
            realistic_payload_installed(
                day, instance_id="pod-b", installed=_with_rate_selector([0, 1, 3])
            )
        )
    only_b = verdict_map(engine.derive(BUILD_SHA))[LEGACY]
    assert only_b.status == UNKNOWN
    assert only_b.eligibility is EligibilityClass.NO_PROBE_INSTALLED

    for day in range(4):
        collector.ingest(
            realistic_payload_installed(
                day, instance_id="pod-a", installed=_with_rate_selector([0, 1, 2])
            )
        )
    merged = verdict_map(engine.derive(BUILD_SHA))[LEGACY]
    assert merged.status == DEAD_CANDIDATE
    assert merged.probe_installed is True
    assert sorted(set_bits(collector.store.probes_installed(BUILD_SHA, RS))) == [0, 1, 2, 3]


def test_a_partially_instrumented_class_reports_the_three_cases_side_by_side(
    collector, engine
):
    """One class, three findings, told apart by `eligibility`."""
    for day in range(4):
        collector.ingest(
            realistic_payload_installed(day, installed=_with_rate_selector([0, 1, 3]))
        )
    v = verdict_map(engine.derive(BUILD_SHA))
    # observable, instrumented, and it ran
    assert v[PICK].status == LIVE
    assert v[PICK].eligibility is EligibilityClass.OBSERVABLE
    # observable, but nothing ever instrumented it (#18)
    assert v[LEGACY].status == UNKNOWN
    assert v[LEGACY].eligibility is EligibilityClass.NO_PROBE_INSTALLED
    # the manifest says it cannot be covered at all (C51)
    maxretries = v[f"{CONSTANTS}#maxRetries()I"]
    assert maxretries.status == NOT_DYNAMICALLY_OBSERVABLE
    assert maxretries.eligibility is EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE


def test_a_later_tier1_disabled_pod_does_not_withdraw_an_earlier_observation(
    ingested_installed, engine
):
    """The mirror error, guarded. The mask merges as a UNION, so a pod that
    arrives with tier-1 off cannot un-instrument what another pod really did
    instrument -- the earlier window's zero bit was a real observation and
    stays one (C53 re-derives, it does not forget)."""
    before = engine.reconcile(BUILD_SHA)
    assert len(before.by_status(DEAD_CANDIDATE)) == 1

    # A pod redeploys with tier-1 off. Its mask is all-zero -- but the union
    # with what the earlier pods installed still says the index was watched,
    # so the candidate correctly SURVIVES. Losing it here would be the mirror
    # error: the earlier observation really did happen.
    ingested_installed.ingest(
        realistic_payload_installed(4, installed=TIER1_DISABLED, probes_set={})
    )
    after = engine.reconcile(BUILD_SHA)
    assert len(after.by_status(DEAD_CANDIDATE)) == 1


# ======================================================================
# 8. the operator surface
# ======================================================================


@pytest.fixture
def api_tier1_off(ingested_tier1_disabled, engine):
    return QueryService(engine, collector=ingested_tier1_disabled)


def test_instrumentation_gaps_names_a_misconfigured_deployment(api_tier1_off):
    """The whole point of surfacing this: a deployment with tier-1 off must
    look like a configuration problem, not like a dead codebase."""
    out = api_tier1_off.instrumentation_gaps(BUILD_SHA)
    assert out["maskReported"] is True
    # NOT ONE index was installed anywhere -- the signature is read off the
    # masks, so the two classes that never reported at all (NeverLoaded, and
    # the test class) cannot dilute it away.
    assert out["installedIndices"] == 0
    assert out["classesWithNothingInstalled"] == 6
    assert out["observableButNeverInstrumented"] == 9
    assert out["byEligibility"]["OBSERVABLE"] == 2  # the two unreported classes
    assert "ax.tier1.enabled=false" in out["diagnosis"]
    assert "could not look" in out["diagnosis"]
    assert "never written by anything" in out["readingRule"]


def test_instrumentation_gaps_lists_the_classes_and_methods(collector, engine):
    for day in range(4):
        collector.ingest(
            realistic_payload_installed(day, installed=_with_rate_selector([0, 1, 3]))
        )
    api = QueryService(engine, collector=collector)
    out = api.instrumentation_gaps(BUILD_SHA)
    assert out["observableButNeverInstrumented"] == 1
    top = out["byClass"][0]
    assert top["class"] == RS
    assert top["installedIndices"] == 3
    assert top["uninstrumentedMethods"] == [{"method": "legacyFallback()V", "idx": 2}]
    assert top["uninstrumentedMethodsTruncated"] == 0
    assert "frame-emission skips" in out["diagnosis"]


def test_instrumentation_gaps_is_bounded_for_a_large_build(api_tier1_off):
    """The totals stay complete; only the listing is capped, because this
    answer is read by an LLM with a context budget."""
    out = api_tier1_off.instrumentation_gaps(BUILD_SHA, limit=1, methods_per_class=1)
    assert out["observableButNeverInstrumented"] == 9  # unchanged by the cap
    assert out["classes"] == 8
    assert out["classesListed"] == 1
    assert len(out["byClass"]) == 1
    assert len(out["byClass"][0]["uninstrumentedMethods"]) == 1
    assert out["byClass"][0]["uninstrumentedMethodsTruncated"] == 3


def test_instrumentation_gaps_says_when_no_agent_reported_a_mask(ingested, engine):
    out = QueryService(engine, collector=ingested).instrumentation_gaps(BUILD_SHA)
    assert out["maskReported"] is False
    assert out["maskSupportedByStore"] is True
    assert "pre-#18 agent" in out["diagnosis"]
    assert "Upgrade the agent" in out["diagnosis"]


def test_a_zero_candidate_count_is_explained_where_it_is_reported(api_tier1_off):
    out = api_tier1_off.dead_candidates(BUILD_SHA)
    assert out["count"] == 0
    assert out["instrumentation"]["observableButNeverInstrumented"] > 0
    assert "misconfigured deployment" in out["instrumentation"]["note"]


def test_the_summary_carries_the_instrumentation_block(api_tier1_off):
    block = api_tier1_off.summary(BUILD_SHA)["instrumentation"]
    assert block["maskReported"] is True
    assert block["classesWithMask"] == 6
    assert block["observableButNeverInstrumented"] > 0


def test_agent_health_reports_strip_mask_missing(collector, engine):
    for day in range(4):
        collector.ingest(realistic_payload_installed(day, strip_mask_missing=2))
    health = QueryService(engine, collector=collector).agent_health(BUILD_SHA)
    assert health["stripMaskMissingTotal"] == 8
    assert health["stripMaskMissingWindows"] == 4
    assert "LOST, never invented" in health["stripMaskMissingNote"]
    assert health["ingest"]["stripMaskMissingTotal"] == 8


def test_the_mcp_surface_exposes_the_gap_query(ingested_tier1_disabled, engine):
    mcp = McpServer(QueryService(engine, collector=ingested_tier1_disabled))
    tool = mcp.tools["gt_instrumentation_gaps"]
    assert "bug #18" in tool.description
    out = tool.fn({"buildSha": BUILD_SHA})
    assert out["observableButNeverInstrumented"] > 0


def test_a_verdict_carries_the_mask_state_on_the_wire(ingested_installed, engine):
    row = QueryService(engine).verdicts_by_class(BUILD_SHA, RS)
    by_method = {r["method"]: r for r in row}
    assert by_method["legacyFallback"]["probeInstalled"] is True
    assert by_method["legacyFallback"]["probeInstallMaskReported"] is True
    assert by_method["legacyFallback"]["eligibility"] == "OBSERVABLE"


def test_the_wire_shape_of_an_uninstrumented_verdict(ingested_tier1_disabled, engine):
    row = QueryService(engine).verdicts_by_class(BUILD_SHA, RS)
    legacy = next(r for r in row if r["method"] == "legacyFallback")
    assert legacy["status"] == UNKNOWN
    assert legacy["eligibility"] == "NO_PROBE_INSTALLED"
    assert legacy["probeInstalled"] is False


def test_the_payload_shape_is_the_contract_example_plus_one_key(collector):
    """A realistic v2 body carrying `probesInstalled`, ingested verbatim.

    `schemaVersion` stays 2: the key is additive and the collector reads only
    named keys, so bumping the wire version to announce it would make every
    deployed collector drop every window (bug #17's lesson).
    """
    body = {
        "schemaVersion": 2,
        "buildSha": BUILD_SHA,
        "artifact": "checkout-service",
        "instanceId": "pod-7f3a",
        "windowStartMs": 1757671200000,
        "windowEndMs": 1757671260000,
        "agentHealth": {
            "transformFailures": 0,
            "classesSkipped": {"noManifestEntry": 12},
            "ringDropped": 0,
            "clockNs": 26,
            "clockDegraded": False,
            "degraded": False,
            "environment": "production",
            "livenessEvidence": True,
            "testRunnerDetected": False,
            "stripMaskMissing": 0,
        },
        # Carries ONLY `agentHealth.environment` -- the canonical spelling
        # CONTRACTS 2 pins and the one the real agent emits. See section 9:
        # the classifier used to miss it and rejected every such window.
        "classesLoaded": [RS],
        "coverage": [
            {
                "class": RS,
                "schemaHash": "5f2a0001",
                "probes": probes(0),
                "probesInstalled": installed_mask(0, 1, 2, 3),
            }
        ],
        "tier2": [],
    }
    result = collector.ingest(body)
    assert result.accepted
    assert result.install_masks_merged == 1
    assert result.records_without_install_mask == 0
    assert sorted(set_bits(collector.store.probes_installed(BUILD_SHA, RS))) == [0, 1, 2, 3]


def test_a_dropped_mask_is_reported_separately_from_an_absent_one(caplog):
    """"The store could not keep the mask" and "the agent never sent one" need
    different operator actions, so they are different counters."""
    from ax_server.collector.service import CollectorService

    class MaskBlindStore:
        def record_window(self, w):
            return None

        def merge_coverage(self, build_sha, cls, schema_hash, probes_):
            return None

        def coverage(self, build_sha, cls):
            return None

    collector = CollectorService(MaskBlindStore())  # type: ignore[arg-type]
    with caplog.at_level("WARNING"):
        result = collector.ingest(realistic_payload_installed(0))
    assert result.install_masks_dropped == 6
    assert result.records_without_install_mask == 0
    assert result.to_json()["installMasksDropped"] == 6


def test_the_empty_string_the_agent_writes_is_an_all_zero_mask_not_an_absent_one(
    collector,
):
    """Wire-compatibility with the real producer.

    `ax-agent`'s Batch.java always writes the key and writes `""` when it
    could not determine the mask (counting `agentHealth.stripMaskMissing`).
    `""` therefore means "this JVM installed nothing here" -- the intended
    bias of losing a candidate rather than inventing one -- and must NOT be
    folded into the absent case, which means "a pre-#18 agent said nothing".
    """
    body = realistic_payload(0)
    for rec in body["coverage"]:
        rec["probes"] = probes()
        rec["probesInstalled"] = ""
    body["agentHealth"]["stripMaskMissing"] = 6
    result = collector.ingest(body)
    assert result.install_masks_merged == 6
    assert result.records_without_install_mask == 0
    mask = collector.store.probes_installed(BUILD_SHA, RS)
    assert mask is not None and popcount(mask) == 0


# ======================================================================
# 9. found while building the fixture above: bug #17, second occurrence
# ======================================================================


def test_the_canonical_environment_spelling_is_accepted(collector):
    """CONTRACTS 2 pins `agentHealth.environment` as the CANONICAL production
    classification, and `ax-agent`'s Batch.java writes `environment` INSIDE
    `agentHealth`. `classification.py` listed only the aliases, so every
    window from the real agent was rejected 403 `not_production_classified`:
    100% data loss, reported as a misconfigured JVM.

    This is bug #17's exact shape -- every server fixture used
    `jvmClassification`, and the agent's smoke listener accepts any body -- so
    the regression test pins the canonical spelling the way bug #17's pins
    both readable schema versions.
    """
    body = realistic_payload_installed(0)
    del body["jvmClassification"]
    body["agentHealth"]["environment"] = "production"
    result = collector.ingest(body)
    assert result.accepted
    window = collector.store.observed_windows(BUILD_SHA)[0]
    assert window.environment == "production"
    assert window.production is True


def test_a_non_production_canonical_label_is_still_refused(collector):
    """The added lookup path is a place to LOOK, never a way to pass: the
    allowlist and the fail-closed default are untouched."""
    body = realistic_payload_installed(0)
    del body["jvmClassification"]
    body["agentHealth"]["environment"] = "staging"
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.NOT_PRODUCTION


def test_an_unclassified_window_is_still_failed_closed(collector):
    body = realistic_payload_installed(0)
    del body["jvmClassification"]
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.NOT_PRODUCTION
