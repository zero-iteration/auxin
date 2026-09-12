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

from ax_server.store.models import IngestWindow, Window

__all__ = ["IngestAudit", "Store", "WindowAttribution"]


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
