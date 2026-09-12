"""Static reachability -- a CORROBORATING signal, never a basis for deletion.

VALIDATION.md A5 measured static call graphs missing **61% of methods that
actually execute** (Samhi et al., ISSTA 2024, 13 tools x 1,000 apps). SPARK
missed up to 100%. Of the missed methods, **79.7% are transitive consequences**
of a missed edge, not missed entry points.

Three rules follow, and they are the whole design of this module:

1. **`None` means unknown and is NEVER False.** A graph that cannot resolve a
   call has told us nothing, and nothing is not evidence of death.
2. **No cascade (C43).** If a method's only callers are themselves unreachable,
   we return `None`, not `False`. Concluding "the caller is dead, therefore the
   callee is dead" is exactly the transitive inference a 61%-unsound graph
   gets wrong.
3. **`<clinit>` is always reachable (C44).** It is the single most-missed
   method kind (56,708 occurrences) and ~45% of cross-tool disagreement.

`False` is therefore reserved for the narrow case where the analyser demonstrably
looked: the method is a node in the graph and the whole-program scan found no
caller at all.
"""

from dataclasses import dataclass

from ax_server.analysis.manifest import Manifest
from ax_server.analysis.models import MethodRef

__all__ = [
    "Reachability",
    "ReachabilityResult",
    "compute_reachability",
    "entry_point_kinds",
]


@dataclass(frozen=True, slots=True)
class ReachabilityResult:
    reachable: frozenset[MethodRef]
    #: Methods the graph knows about at all (source or target of any edge).
    nodes: frozenset[MethodRef]
    #: Methods with at least one inbound edge we refuse to trust.
    unresolved_targets: frozenset[MethodRef]
    #: Methods whose only inbound edges are C52 `no-op` sites.
    no_op_only_targets: frozenset[MethodRef]
    #: Methods reachable only through a library<->test SCC (C50.2).
    test_only: frozenset[MethodRef]
    roots: frozenset[MethodRef]
    dropped_test_roots: int
    dropped_test_edges: int


class Reachability:
    """Answers `static_reachable` for one manifest."""

    def __init__(self, manifest: Manifest, result: ReachabilityResult) -> None:
        self._manifest = manifest
        self._r = result
        self._inbound_resolved = frozenset(
            e.dst for e in manifest.call_edges if e.resolved
        )

    @property
    def result(self) -> ReachabilityResult:
        return self._r

    def classify(self, ref: MethodRef) -> bool | None:
        """True / False / None. `None` is the default, not `False`."""
        # C44: never treat a <clinit> as unreachable.
        if ref.name == "<clinit>":
            return True
        if ref in self._r.reachable:
            return True
        # An inbound edge the graph could not resolve means the graph has an
        # opinion it cannot defend. That is not evidence of death.
        if ref in self._r.unresolved_targets:
            return None
        # C52: a no-op call site does not keep the target alive, but it also
        # does not prove the target dead.
        if ref in self._r.no_op_only_targets:
            return None
        if ref in self._r.test_only:
            # C50.2: reachable ONLY from its own test. The test does not make
            # it live -- and it does not make it provably dead either.
            return None
        if ref in self._r.nodes:
            inbound = self._inbound(ref)
            if inbound:
                # Callers exist but none of them are reachable. Saying False
                # here IS the cascade. Refuse.
                return None
            return False
        # Absent from the graph entirely: the analyser never saw it.
        return None

    def _inbound(self, ref: MethodRef) -> bool:
        return ref in self._inbound_resolved


def compute_reachability(manifest: Manifest) -> Reachability:
    test_classes = manifest.test_classes
    test_methods: set[MethodRef] = set()
    for cls, method in manifest.iter_methods():
        if cls.is_test or method.is_test:
            test_methods.add(MethodRef(cls.name, method.name, method.desc))

    def is_test_ref(ref: MethodRef) -> bool:
        return ref in test_methods or ref.cls in test_classes

    # ---- roots -------------------------------------------------------
    # C50.2: a test entry point is NOT a production root. If it were, every
    # tested method would be permanently reachable and the product could only
    # ever propose deleting untested code.
    roots: set[MethodRef] = set()
    dropped_test_roots = 0
    for ep in manifest.entry_points:
        ref = ep.ref
        if ep.is_test or is_test_ref(ref):
            dropped_test_roots += 1
            continue
        roots.add(ref)

    # ---- edges -------------------------------------------------------
    scc_pairs: set[tuple[MethodRef, MethodRef]] = set()
    for scc in manifest.library_test_sccs:
        for a in scc:
            for b in scc:
                scc_pairs.add((a, b))

    forward: dict[MethodRef, list[MethodRef]] = {}
    nodes: set[MethodRef] = set()
    unresolved_targets: set[MethodRef] = set()
    resolved_targets: set[MethodRef] = set()
    no_op_targets: set[MethodRef] = set()
    dropped_test_edges = 0

    for edge in manifest.call_edges:
        nodes.add(edge.src)
        nodes.add(edge.dst)
        if not edge.resolved:
            unresolved_targets.add(edge.dst)
            continue
        # A test calling a library does not make the library live.
        if is_test_ref(edge.src):
            dropped_test_edges += 1
            continue
        # Intra-SCC edges cannot bootstrap their own component.
        if (edge.src, edge.dst) in scc_pairs:
            dropped_test_edges += 1
            continue
        if edge.semantics == "no-op":
            no_op_targets.add(edge.dst)
            continue
        resolved_targets.add(edge.dst)
        forward.setdefault(edge.src, []).append(edge.dst)

    # ---- traversal ---------------------------------------------------
    # Over-approximate on purpose: every <clinit> of a reachable type is a root
    # too (C44), applied to fixpoint because a <clinit> can reach more types.
    clinit_by_class: dict[str, list[MethodRef]] = {}
    for ref in nodes | roots:
        if ref.name == "<clinit>":
            clinit_by_class.setdefault(ref.cls, []).append(ref)

    reachable: set[MethodRef] = set()
    frontier = list(roots)
    while frontier:
        while frontier:
            current = frontier.pop()
            if current in reachable:
                continue
            reachable.add(current)
            frontier.extend(forward.get(current, ()))
        added = False
        for ref in list(reachable):
            for clinit in clinit_by_class.get(ref.cls, ()):
                if clinit not in reachable:
                    frontier.append(clinit)
                    added = True
        if not added:
            break

    # Methods whose ONLY inbound resolved edges came from tests or from inside
    # a library<->test SCC.
    test_only: set[MethodRef] = set()
    inbound_all: dict[MethodRef, list[bool]] = {}
    for edge in manifest.call_edges:
        if not edge.resolved:
            continue
        from_test = is_test_ref(edge.src) or (edge.src, edge.dst) in scc_pairs
        inbound_all.setdefault(edge.dst, []).append(from_test)
    for dst, flags in inbound_all.items():
        if flags and all(flags) and dst not in reachable:
            test_only.add(dst)

    no_op_only = {d for d in no_op_targets if d not in resolved_targets and d not in reachable}

    return Reachability(
        manifest,
        ReachabilityResult(
            reachable=frozenset(reachable),
            nodes=frozenset(nodes),
            unresolved_targets=frozenset(unresolved_targets),
            no_op_only_targets=frozenset(no_op_only),
            test_only=frozenset(test_only),
            roots=frozenset(roots),
            dropped_test_roots=dropped_test_roots,
            dropped_test_edges=dropped_test_edges,
        ),
    )


def entry_point_kinds(manifest: Manifest) -> dict[str, str]:
    """Map class name -> the entry-point kind it is exposed by, for C55 keying."""
    out: dict[str, str] = {}
    for ep in manifest.entry_points:
        out.setdefault(ep.cls, ep.kind)
    return out
