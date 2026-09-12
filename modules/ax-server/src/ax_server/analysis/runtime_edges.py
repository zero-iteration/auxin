"""The SAMPLED runtime call graph -- a presence-only signal.

This module exists to hold one rule, and the rule is the whole reason the
module is separate from `reachability.py`:

    **PRESENCE is evidence. ABSENCE is not.**

CONTRACTS 2 v3: the edge graph is sampled once per 1-in-N entries into a
tier-2 boundary root, depth-bounded (`edgesTruncatedDepth`), per-root-bounded
(`edgesTruncatedRoot`), distinct-bounded (`edgesTruncatedDistinct`),
drop-on-full (`edgesDropped`), latchable-off (`edgeTierFailures`) and
reapable (`edgeTracesReaped`). At the default 1-in-1024, an edge on a path
taken a thousand times may simply never be recorded. Measured in bench G6:
the sampled arm recorded 32 edges where the unsampled arm recorded 32,768.

So a missing edge carries exactly zero information, and an observed one is
proof that a real caller really called a real callee in production.

Why this is NOT merged with the static graph
--------------------------------------------
`reachability.py` answers a different question with the opposite error mode:

    static  graph:  OVER-approximate -- includes edges that never execute;
                    and A5 measured it missing 61% of methods that DO execute.
    runtime graph:  UNDER-approximate -- everything in it really happened,
                    and most of what happened is not in it.

One boolean cannot carry both. A merged "reachable" flag would be true for a
call site that is dead in practice (static) and false for a hot path that
sampling missed (runtime) -- and a reader could not tell which. Keeping them
separate in the `Verdict` is the only thing that makes either usable: static
`False` is a weak argument towards death, runtime `True` is a hard argument
towards life, and neither substitutes for the other.

`runtime_reachable` is therefore typed `True | None`. There is deliberately no
`False`.
"""

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import datetime

from ax_server.analysis.manifest import Manifest
from ax_server.collector.known_methods import MethodIndex
from ax_server.store.models import EdgeAggregate, EdgeEndpoint
from ax_server.store.port import EdgeStore, Store

__all__ = [
    "EdgeEvidence",
    "RuntimeCallGraph",
    "load_runtime_call_graph",
    "manifest_method_index",
]


@dataclass(frozen=True, slots=True)
class EdgeEvidence:
    """One direction's observed edges for one method.

    ``sampled_observations`` is a SUM OF RAW OBSERVATIONS IN SAMPLED TRACES.
    It is never a call count and must never be rendered as one; it travels
    with its sample rate or not at all.
    """

    observed_edges: int = 0
    sampled_observations: int = 0
    sample_rate_min: int = 0
    sample_rate_max: int = 0
    first_seen: datetime | None = None
    last_seen: datetime | None = None

    @property
    def present(self) -> bool:
        """True => a real caller/callee was observed. False => NOTHING KNOWN."""
        return self.observed_edges > 0

    @property
    def uniform_sample_rate(self) -> int | None:
        """The single rate behind the count, or None when the contributing
        windows disagree (or declared none). None means "do not scale"."""
        if self.sample_rate_min and self.sample_rate_min == self.sample_rate_max:
            return self.sample_rate_min
        return None

    @classmethod
    def of(cls, edges: Iterable[EdgeAggregate]) -> "EdgeEvidence":
        rows = list(edges)
        if not rows:
            return cls()
        rates = [r for row in rows for r in (row.sample_rate_min, row.sample_rate_max) if r]
        firsts = [row.first_seen for row in rows if row.first_seen]
        lasts = [row.last_seen for row in rows if row.last_seen]
        return cls(
            observed_edges=len(rows),
            sampled_observations=sum(row.sampled_observations for row in rows),
            sample_rate_min=min(rates) if rates else 0,
            sample_rate_max=max(rates) if rates else 0,
            first_seen=min(firsts) if firsts else None,
            last_seen=max(lasts) if lasts else None,
        )


@dataclass(frozen=True, slots=True)
class RuntimeCallGraph:
    """A build's observed edges, read once per analysis run.

    The presence sets are loaded eagerly (one query, and they are what the
    rule needs for every method); counts are fetched per method, and only for
    the methods that actually have an observed edge -- there is nothing to
    count for the rest, and asking would be a query per dead method.
    """

    build_sha: str = ""
    #: Methods with at least one OBSERVED inbound edge.
    inbound: frozenset[EdgeEndpoint] = frozenset()
    #: Methods with at least one OBSERVED outbound edge.
    outbound: frozenset[EdgeEndpoint] = frozenset()
    #: sample_rate -> windows. Key 0 = a window that reported the tier with no
    #: rate declared (tier off).
    sample_rates: Mapping[int, int] = field(default_factory=dict)
    #: True when at least one accepted window carried an `edges[]` key at all.
    #: CONTRACTS 2: an absent key and an empty graph are different facts, and
    #: a rule must not narrate a tier that never spoke.
    reported: bool = False
    _store: EdgeStore | None = None

    # -- presence --------------------------------------------------------

    def has_inbound(self, cls: str, idx: int) -> bool:
        """Was this method OBSERVED being called? False means nothing known."""
        return (cls, idx) in self.inbound

    def has_outbound(self, cls: str, idx: int) -> bool:
        return (cls, idx) in self.outbound

    @property
    def armed(self) -> bool:
        """True when some window declared a real sample rate."""
        return any(rate > 0 for rate in self.sample_rates)

    @property
    def uniform_sample_rate(self) -> int | None:
        rates = {rate for rate in self.sample_rates if rate > 0}
        return next(iter(rates)) if len(rates) == 1 else None

    # -- counts, per method ----------------------------------------------

    def callers_of(self, cls: str, idx: int) -> list[EdgeAggregate]:
        if self._store is None or not self.has_inbound(cls, idx):
            return []
        return self._store.callers_of(self.build_sha, cls, idx)

    def callees_of(self, cls: str, idx: int) -> list[EdgeAggregate]:
        if self._store is None or not self.has_outbound(cls, idx):
            return []
        return self._store.callees_of(self.build_sha, cls, idx)

    def inbound_evidence(self, cls: str, idx: int) -> EdgeEvidence:
        return EdgeEvidence.of(self.callers_of(cls, idx))

    def outbound_evidence(self, cls: str, idx: int) -> EdgeEvidence:
        return EdgeEvidence.of(self.callees_of(cls, idx))

    def hot_edges(self, limit: int = 50) -> list[EdgeAggregate]:
        if self._store is None:
            return []
        return self._store.hot_edges(self.build_sha, limit)

    # -- disclosure ------------------------------------------------------

    def caveat(self) -> str:
        """The sentence that must accompany any answer built from this graph."""
        rate = self.uniform_sample_rate
        scale = f"1-in-{rate}" if rate else "an undeclared or mixed rate"
        return (
            f"Observed edges are sampled at {scale} per root entry and are additionally "
            "depth-, per-root- and distinct-bounded with drop-on-full. PRESENCE of an "
            "edge is evidence that the call really happened; ABSENCE is not evidence of "
            "anything and must never be read as 'nothing calls this' (CONTRACTS 2 v3)."
        )


def load_runtime_call_graph(store: Store, build_sha: str) -> RuntimeCallGraph:
    """Read the observed graph for a build. Empty when the adapter has none."""
    if not isinstance(store, EdgeStore):
        return RuntimeCallGraph(build_sha=build_sha)
    inbound, outbound = store.edge_endpoints(build_sha)
    rates = store.edge_sample_rates(build_sha)
    return RuntimeCallGraph(
        build_sha=build_sha,
        inbound=frozenset(inbound),
        outbound=frozenset(outbound),
        sample_rates=dict(rates),
        reported=bool(rates),
        _store=store,
    )


def manifest_method_index(manifest: Manifest, build_sha: str | None = None) -> MethodIndex:
    """Adapter: the collector's `KnownMethods` port over a `Manifest`.

    Lives here rather than in `collector/` because the dependency order is
    store -> collector -> analysis, so the manifest reader cannot be imported
    downward. The collector defines the port; this supplies it.
    """
    return MethodIndex(
        build_sha or manifest.build_sha,
        ((klass.name, method.idx) for klass, method in manifest.iter_methods()),
        source="auxin-manifest.json",
    )
