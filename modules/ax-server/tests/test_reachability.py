"""A5 -- static reachability is corroborating only. None is never False."""

from ax_server.analysis.manifest import load_manifest
from ax_server.analysis.models import MethodRef
from ax_server.analysis.reachability import compute_reachability

R = MethodRef


def _r(manifest):
    return compute_reachability(manifest)


def test_entry_point_and_its_callee_are_reachable(manifest):
    r = _r(manifest)
    assert r.classify(R("com.acme.shipping.RateSelector", "pick",
                        "(Ljava/util/List;)Lcom/acme/Rate;")) is True
    assert r.classify(R("com.acme.shipping.RateSelector", "normalise",
                        "(Lcom/acme/Rate;)Lcom/acme/Rate;")) is True


def test_a_node_with_no_caller_at_all_is_the_only_False_case(manifest):
    r = _r(manifest)
    assert r.classify(R("com.acme.shipping.RateSelector", "legacyFallback", "()V")) is False


def test_unresolved_inbound_edge_yields_None_not_False(manifest):
    r = _r(manifest)
    # CONTRACTS 1 makes `unresolved` a first-class value; A5 measured 61% of
    # executed methods missing from static graphs.
    assert r.classify(R("com.acme.billing.MonthEndReport", "render", "()V")) is None


def test_method_absent_from_the_graph_is_None(manifest):
    r = _r(manifest)
    assert r.classify(R("com.acme.util.Constants", "maxRetries", "()I")) is None


def test_no_cascade_from_an_unreachable_caller():
    """C43: an unreachable caller must not make its callee 'unreachable'."""
    manifest = load_manifest(
        {
            "buildSha": "b",
            "classes": [
                {"name": "C", "methods": [
                    {"idx": 0, "name": "root", "desc": "()V"},
                    {"idx": 1, "name": "mid", "desc": "()V"},
                    {"idx": 2, "name": "leaf", "desc": "()V"},
                ]}
            ],
            "entryPoints": [],
            "callEdges": [
                {"from": "C#mid()V", "to": "C#leaf()V", "resolution": "exact"},
            ],
        }
    )
    r = compute_reachability(manifest)
    # `mid` is a node with no caller -> the graph genuinely found nothing.
    assert r.classify(R("C", "mid", "()V")) is False
    # `leaf` HAS a caller; that caller is unreachable. Concluding "leaf is dead"
    # is the cascade. We refuse.
    assert r.classify(R("C", "leaf", "()V")) is None


def test_clinit_is_always_reachable():
    manifest = load_manifest({"buildSha": "b", "classes": [], "callEdges": []})
    r = compute_reachability(manifest)
    assert r.classify(R("com.anything.Whatever", "<clinit>", "()V")) is True


def test_clinit_of_a_reachable_type_seeds_further_reachability():
    manifest = load_manifest(
        {
            "buildSha": "b",
            "classes": [],
            "entryPoints": [{"class": "A", "method": "go", "desc": "()V", "kind": "Main"}],
            "callEdges": [
                {"from": "A#<clinit>()V", "to": "B#init()V", "resolution": "exact"},
            ],
        }
    )
    r = compute_reachability(manifest)
    assert r.classify(R("B", "init", "()V")) is True


def test_no_op_edge_does_not_confer_reachability_but_blocks_False():
    manifest = load_manifest(
        {
            "buildSha": "b",
            "classes": [],
            "entryPoints": [{"class": "A", "method": "go", "desc": "()V", "kind": "Main"}],
            "callEdges": [
                {"from": "A#go()V", "to": "B#probe()V",
                 "resolution": "exact", "semantics": "no-op"},
            ],
        }
    )
    r = compute_reachability(manifest)
    assert r.classify(R("B", "probe", "()V")) is None


def test_test_entry_points_are_not_production_roots(manifest):
    r = _r(manifest)
    assert r.result.dropped_test_roots == 1
    assert R("com.acme.lib.LibOnlyUsedByTestTest", "testHelper", "()V") not in r.result.reachable
