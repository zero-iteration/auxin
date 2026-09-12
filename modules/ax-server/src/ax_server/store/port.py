"""The store port.

`Store` is CONTRACTS 3 verbatim -- those eight methods and nothing else.
Anything an adapter can usefully offer beyond the frozen port lives in a
SEPARATE optional ABC that callers feature-detect with `isinstance`. That
keeps 3 frozen while still letting the SQLite adapter attribute merges to a
real window timestamp instead of wall-clock "now".
"""

from abc import ABC, abstractmethod
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime

from ax_server.store.models import EdgeAggregate, EdgeEndpoint, IngestWindow, Window

__all__ = [
    "EdgeStore",
    "IngestAudit",
    "ProbeInstallStore",
    "Store",
    "WindowAttribution",
]


class Store(ABC):
    """CONTRACTS 3 -- FROZEN. Do not add abstract methods to this class."""

    @abstractmethod
    def record_window(self, w: IngestWindow) -> None:
        """Persist an observation window together with its `agentHealth`."""

    @abstractmethod
    def merge_coverage(self, build_sha: str, cls: str, schema_hash: str, probes: bytes) -> None:
        """Bitwise-OR `probes` into stored coverage. NEVER an overwrite.

        Raises:
            SchemaMismatch: if `schema_hash` differs from the stored hash for
                this (build_sha, cls). Must not silently merge and must not
                silently report zero coverage.
        """

    @abstractmethod
    def first_seen(self, build_sha: str, cls: str, idx: int) -> datetime | None:
        """When probe `idx` was first observed set. None if never observed."""

    @abstractmethod
    def last_seen(self, build_sha: str, cls: str, idx: int) -> datetime | None:
        """The most recent window in which probe `idx` was reported set."""

    @abstractmethod
    def coverage(self, build_sha: str, cls: str) -> bytes | None:
        """The merged probe bitset for a class, or None if never merged."""

    @abstractmethod
    def observed_windows(self, build_sha: str) -> list[Window]:
        """Every recorded window for a build, ascending by start time."""

    @abstractmethod
    def class_loaded_ever(self, build_sha: str, cls: str) -> bool:
        """C10: "never loaded" must be distinguishable from "loaded but never
        invoked". This is tracked separately from the probe bits."""

    @abstractmethod
    def tier2_buckets(self, build_sha: str, cls: str, idx: int, since: datetime) -> list[int]:
        """Element-wise sum of the log-linear buckets recorded since `since`."""


class WindowAttribution(ABC):
    """OPTIONAL port extension, not part of CONTRACTS 3.

    `merge_coverage` has no timestamp parameter in the frozen port, so an
    adapter would otherwise have to stamp first_seen/last_seen with wall-clock
    "now". This lets the collector attribute a batch of merges to the window
    they actually came from.
    """

    @contextmanager
    @abstractmethod
    def attribute_to(self, when: datetime) -> Iterator[None]:
        """Within this context, merges are timestamped `when`."""
        raise NotImplementedError


class EdgeStore(ABC):
    """OPTIONAL port extension, not part of CONTRACTS 3 -- the SCOPE-v3
    sampled runtime call-edge tier.

    Additive by construction: `Store` keeps exactly the eight frozen
    signatures, and an adapter that does not implement this port simply has no
    edge graph. Callers feature-detect with `isinstance`, as they already do
    for `WindowAttribution` and `IngestAudit`.

    Merge semantics here are the OPPOSITE of `merge_coverage`'s and that is
    the whole point:

    * coverage is an **idempotent bitwise OR** -- the agent sends an
      accumulated bitset, so re-sending a window changes nothing;
    * edge counts are **additive** -- the agent sends per-window observation
      counts, so two windows, or two pods, sum.

    Conflating them loses data in one direction and fabricates it in the
    other, so nothing in this port is allowed to look like `merge_coverage`.
    """

    @abstractmethod
    def record_edges(self, w: IngestWindow) -> int:
        """Persist `w.edges` for this window and ADD them into the aggregate.

        Returns the number of edge records folded in. Idempotent it is not:
        the same window replayed twice counts twice, exactly as `tier2[]`
        does, because both carry per-window deltas rather than accumulated
        state.
        """

    @abstractmethod
    def callers_of(self, build_sha: str, cls: str, idx: int) -> list[EdgeAggregate]:
        """Observed inbound edges for `(cls, idx)`, highest count first.

        An empty list means "nothing was sampled", NEVER "nothing calls it".
        """

    @abstractmethod
    def callees_of(self, build_sha: str, cls: str, idx: int) -> list[EdgeAggregate]:
        """Observed outbound edges for `(cls, idx)`, highest count first."""

    @abstractmethod
    def hot_edges(self, build_sha: str, limit: int = 50) -> list[EdgeAggregate]:
        """The highest-count observed edges. Relative, never absolute."""

    @abstractmethod
    def edge_endpoints(self, build_sha: str) -> tuple[set[EdgeEndpoint], set[EdgeEndpoint]]:
        """`(methods with an observed inbound edge, with an observed outbound
        edge)`. One query for a whole analysis run; PRESENCE only, because
        presence is the only thing this tier can prove."""

    @abstractmethod
    def edge_sample_rates(self, build_sha: str) -> dict[int, int]:
        """`sample_rate -> number of windows recorded at that rate`.

        Surfaced with every count. A count of 4 at 1-in-1024 is not 4 calls.
        """


class ProbeInstallStore(ABC):
    """OPTIONAL port extension, not part of CONTRACTS 3 -- the installed-probe
    mask (`coverage[].probesInstalled`, bug #18).

    Why this is a port extension and not a `Store` method: CONTRACTS 3's eight
    signatures are frozen and a test pins the set, exactly as for
    `WindowAttribution`, `IngestAudit` and `EdgeStore`. An adapter that does
    not implement this simply has no mask -- and "no mask" is a state the
    analysis already has to handle honestly, because a pre-#18 agent produces
    it too.

    Merge semantics are the SAME as `merge_coverage`'s and that is deliberate:
    a **bitwise OR across pods and windows**. Different JVMs legitimately
    install different probe sets -- a class-file-<55 fallback on one node, a
    `frameEmissionUnsupported` method on another -- and the union is the
    conservative answer to the only question the mask is asked: "could SOME
    JVM have observed this index?" Intersecting would withdraw an index that
    one JVM really was watching; summing is meaningless for a bitset.

    ROW EXISTENCE IS ITSELF A FACT. `probes_installed` returns `None` when no
    window ever declared a mask for the class, and `b""` (or any all-zero
    buffer) when a window declared that it installed nothing. The first is
    silence; the second is a positive report that no index here can ever be
    written. Collapsing them re-creates bug #18 in the other direction.
    """

    @abstractmethod
    def merge_probes_installed(
        self, build_sha: str, cls: str, schema_hash: str, installed: bytes
    ) -> None:
        """Bitwise-OR `installed` into the stored mask. NEVER an overwrite.

        Raises:
            SchemaMismatch: if `schema_hash` differs from the stored hash for
                this `(build_sha, cls)`. The mask is indexed by build-time
                probe index, so merging across a schema change would move the
                gate onto the wrong methods.
        """

    @abstractmethod
    def probes_installed(self, build_sha: str, cls: str) -> bytes | None:
        """The merged installed mask for a class.

        `None` means NO window ever reported one. That is not an all-zero
        mask and must not be treated as one.
        """

    @abstractmethod
    def installed_masks(self, build_sha: str) -> dict[str, bytes]:
        """`class -> merged mask`, one query for a whole analysis run.

        A class absent from the mapping never reported a mask; a class present
        with an all-zero value reported that it installed nothing.
        """


class IngestAudit(ABC):
    """OPTIONAL port extension, not part of CONTRACTS 3.

    Rejected ingests are evidence too: a spike in schema mismatches or in
    unclassified JVMs is how you find out an upstream agent is misconfigured.
    Persisting them lets the read-only API surface them.
    """

    @abstractmethod
    def record_reject(
        self,
        *,
        reason: str,
        build_sha: str | None,
        artifact: str | None,
        instance_id: str | None,
        detail: str,
        when: datetime,
    ) -> None:
        """Persist one rejected or discarded ingest for audit."""

    @abstractmethod
    def rejects(self, build_sha: str | None = None, limit: int = 100) -> list[dict[str, object]]:
        """Most recent rejects, newest first."""
