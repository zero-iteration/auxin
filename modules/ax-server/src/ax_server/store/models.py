"""Store-layer value objects. These mirror the CONTRACTS 2 wire shape."""

from dataclasses import dataclass, field
from datetime import UTC, datetime
from collections.abc import Mapping
from types import MappingProxyType
from typing import Any

__all__ = [
    "AgentHealth",
    "CoverageRecord",
    "IngestWindow",
    "Tier2Record",
    "Window",
    "epoch_ms",
    "from_epoch_ms",
]

_EMPTY: Mapping[str, Any] = MappingProxyType({})


def from_epoch_ms(ms: int | None) -> datetime | None:
    """Epoch milliseconds -> timezone-aware UTC datetime."""
    if ms is None:
        return None
    return datetime.fromtimestamp(ms / 1000.0, tz=UTC)


def epoch_ms(dt: datetime | None) -> int | None:
    """Timezone-aware datetime -> epoch milliseconds."""
    if dt is None:
        return None
    if dt.tzinfo is None:
        raise ValueError("naive datetimes are not accepted; pass an aware UTC datetime")
    return int(dt.timestamp() * 1000)


@dataclass(frozen=True, slots=True)
class AgentHealth:
    """CONTRACTS 2 `agentHealth`. Mandatory on every window.

    A window with ``degraded`` true must never be used as evidence of death
    (CONTRACTS 2). It may still be used as evidence of LIFE: a degraded agent
    drops observations, it does not invent them.
    """

    transform_failures: int = 0
    classes_skipped: Mapping[str, int] = field(default_factory=lambda: _EMPTY)
    ring_dropped: int = 0
    clock_ns: int = 0
    clock_degraded: bool = False
    degraded: bool = False
    raw: Mapping[str, Any] = field(default_factory=lambda: _EMPTY)

    @property
    def total_classes_skipped(self) -> int:
        return sum(self.classes_skipped.values())


@dataclass(frozen=True, slots=True)
class CoverageRecord:
    """One entry of CONTRACTS 2 `coverage[]`, already base64-decoded."""

    cls: str
    schema_hash: str
    probes: bytes


@dataclass(frozen=True, slots=True)
class Tier2Record:
    """One entry of CONTRACTS 2 `tier2[]`.

    `buckets` are raw log-linear counts. The agent NEVER sends a percentile
    (CONTRACTS 2 / C31); percentiles are derived server-side.
    """

    cls: str
    idx: int
    calls: int
    errors: int
    error_types: Mapping[str, int]
    buckets: tuple[int, ...]
    bucket_scheme: str


@dataclass(frozen=True, slots=True)
class IngestWindow:
    """A validated CONTRACTS 2 body, ready to be recorded."""

    schema_version: int
    build_sha: str
    artifact: str
    instance_id: str
    window_start_ms: int
    window_end_ms: int
    agent_health: AgentHealth
    classes_loaded: tuple[str, ...] = ()
    coverage: tuple[CoverageRecord, ...] = ()
    tier2: tuple[Tier2Record, ...] = ()
    #: C50.1 -- explicit production classification of the reporting JVM.
    #: FAIL CLOSED: absent classification means this window is NOT usable as
    #: evidence of liveness. The collector rejects such windows outright.
    environment: str = "unclassified"
    production: bool = False
    #: C50.3 -- a test-runner frame was seen in this JVM.
    test_tainted: bool = False
    test_markers: tuple[str, ...] = ()
    received_at: datetime | None = None

    @property
    def start(self) -> datetime:
        return from_epoch_ms(self.window_start_ms)  # type: ignore[return-value]

    @property
    def end(self) -> datetime:
        return from_epoch_ms(self.window_end_ms)  # type: ignore[return-value]


@dataclass(frozen=True, slots=True)
class Window:
    """A persisted observation window, as returned by `Store.observed_windows`."""

    window_id: int
    build_sha: str
    artifact: str
    instance_id: str
    start: datetime
    end: datetime
    degraded: bool
    agent_health: AgentHealth
    environment: str
    production: bool
    test_tainted: bool
    received_at: datetime

    @property
    def duration_seconds(self) -> float:
        return (self.end - self.start).total_seconds()

    @property
    def usable_as_death_evidence(self) -> bool:
        """CONTRACTS 2 + C50: degraded, non-production or test-tainted windows
        can never contribute to a death argument."""
        return not self.degraded and self.production and not self.test_tainted
