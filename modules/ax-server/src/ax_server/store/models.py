"""Store-layer value objects. These mirror the CONTRACTS 2 wire shape."""

from dataclasses import dataclass, field
from datetime import UTC, datetime
from collections.abc import Mapping
from types import MappingProxyType
from typing import Any

__all__ = [
    "AgentHealth",
    "CoverageRecord",
    "ERRORS_BY_CLASS",
    "ERROR_TYPES_LEGACY",
    "ERROR_TYPES_UNAVAILABLE",
    "EdgeAggregate",
    "EdgeEndpoint",
    "EdgeHealth",
    "EdgeRecord",
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
class EdgeHealth:
    """CONTRACTS 2 v3 `agentHealth.edges*` -- the sampled call-edge tier.

    Every one of these counters exists because the edge graph is LOSSY BY
    DESIGN, and each names a distinct way an edge can fail to appear:
    unsampled root, depth cap, per-root cap, distinct-edge cap, ring drop,
    latched-off tier, reaped trace. Together they are the reason
    ``edges[]`` absence can never be evidence of death.

    ``edge_tier_failures`` is deliberately NOT wired to ``degraded``
    (CONTRACTS 2): the tier latching itself off says nothing about the
    coverage probes or the tier-2 timings in the same window, and marking the
    window degraded would throw away good death evidence to report a
    second-order instrumentation fault.
    """

    #: The tier is armed in this JVM. Default false: absence of edges from a
    #: window with `false` here is a configuration fact, not an observation.
    enabled: bool = False
    #: 1-in-N root entries traced. 0 means the agent did not declare one, and
    #: then the counts in `edges[]` are UNINTERPRETABLE -- not "1".
    sample_rate: int = 0
    sampled_roots: int = 0
    recorded: int = 0
    dropped: int = 0
    truncated_depth: int = 0
    truncated_root: int = 0
    truncated_distinct: int = 0
    tier_failures: int = 0
    traces_reaped: int = 0

    @property
    def truncated_total(self) -> int:
        return self.truncated_depth + self.truncated_root + self.truncated_distinct

    @property
    def lossy(self) -> bool:
        """True when this window is KNOWN to have lost edges. False here does
        NOT mean the graph is complete -- sampling loses edges silently, which
        is exactly why absence is never evidence."""
        return bool(self.dropped or self.truncated_total or self.tier_failures
                    or self.traces_reaped)

    def to_json(self) -> dict[str, Any]:
        return {
            "edgesEnabled": self.enabled,
            "edgesSampleRate": self.sample_rate or None,
            "edgesSampledRoots": self.sampled_roots,
            "edgesRecorded": self.recorded,
            "edgesDropped": self.dropped,
            "edgesTruncatedDepth": self.truncated_depth,
            "edgesTruncatedRoot": self.truncated_root,
            "edgesTruncatedDistinct": self.truncated_distinct,
            "edgeTierFailures": self.tier_failures,
            "edgeTracesReaped": self.traces_reaped,
        }


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
    #: CONTRACTS 2 v3, additive. Never affects `degraded`.
    edges: EdgeHealth = field(default_factory=EdgeHealth)
    #: CONTRACTS 2 `agentHealth.livenessEvidence` -- what the AGENT said, and
    #: `None` when it said nothing (a pre-C50 producer). Kept separate from
    #: `IngestWindow.production`, which is what the COLLECTOR concluded: the
    #: two are combined fail-closed, so an explicit `false` from the agent
    #: withdraws the window even if its environment label is allowlisted.
    liveness_evidence: bool | None = None
    #: CONTRACTS 2 `agentHealth.testRunnerDetected`. True => this JVM is
    #: running a test harness and nothing in the window is a liveness signal.
    test_runner_detected: bool = False
    #: CONTRACTS 2 `agentHealth.stripMaskMissing` (bug #18): how many times
    #: this JVM could not determine which probe indices it had actually
    #: installed. The agent then ships an ALL-ZERO `probesInstalled` mask,
    #: losing candidates rather than inventing them -- so a non-zero counter
    #: explains missing candidates and must never be read as death evidence.
    #: Deliberately NOT wired to `degraded`: the coverage bits in the same
    #: window are still perfectly good evidence of LIFE.
    strip_mask_missing: int = 0

    @property
    def total_classes_skipped(self) -> int:
        return sum(self.classes_skipped.values())


#: `(class, idx)` -- the CONTRACTS 1 manifest identity, the SAME identity
#: `tier2[]` uses. Never an agent-internal id, never a name+descriptor string.
EdgeEndpoint = tuple[str, int]


@dataclass(frozen=True, slots=True)
class EdgeRecord:
    """One entry of CONTRACTS 2 v3 `edges[]`, for a single window.

    ``count`` is a raw count of observations **in sampled traces** in this
    window. It is NOT a call count. It is additive across windows and pods --
    unlike `coverage`, which is an idempotent bitwise OR. The two merge
    semantics must never be conflated: OR-ing counts would silently discard
    volume, and summing bitsets is meaningless.
    """

    caller_cls: str
    caller_idx: int
    callee_cls: str
    callee_idx: int
    count: int

    @property
    def caller(self) -> EdgeEndpoint:
        return (self.caller_cls, self.caller_idx)

    @property
    def callee(self) -> EdgeEndpoint:
        return (self.callee_cls, self.callee_idx)

    @property
    def endpoints(self) -> tuple[EdgeEndpoint, EdgeEndpoint]:
        return (self.caller, self.callee)


@dataclass(frozen=True, slots=True)
class EdgeAggregate:
    """`(build_sha, caller, callee) -> summed sampled observations`.

    ``sample_rate_min``/``max`` are carried alongside the count on purpose:
    windows recorded at different 1-in-N rates contribute counts on different
    scales, so a sum whose range is not equal is not even relative. A reader
    that cannot see the rate cannot interpret the number, so the store refuses
    to hand out one without the other.
    """

    build_sha: str
    caller_cls: str
    caller_idx: int
    callee_cls: str
    callee_idx: int
    #: Summed RAW observations in sampled traces. Never a call total.
    sampled_observations: int
    windows: int
    first_seen: datetime | None
    last_seen: datetime | None
    sample_rate_min: int = 0
    sample_rate_max: int = 0

    @property
    def caller(self) -> EdgeEndpoint:
        return (self.caller_cls, self.caller_idx)

    @property
    def callee(self) -> EdgeEndpoint:
        return (self.callee_cls, self.callee_idx)

    @property
    def uniform_sample_rate(self) -> int | None:
        """The one rate every contributing window used, or None if they differ
        (or none was declared). None means "do not scale this count"."""
        if self.sample_rate_min and self.sample_rate_min == self.sample_rate_max:
            return self.sample_rate_min
        return None


@dataclass(frozen=True, slots=True)
class CoverageRecord:
    """One entry of CONTRACTS 2 `coverage[]`, already base64-decoded.

    ``probes_installed`` is `coverage[].probesInstalled` (bug #18): the same
    packing as ``probes``, one bit per manifest probe index, set where the
    emitter ACTUALLY INSTALLED a probe. The array is sized to the manifest's
    `probeCount`, but the emitter skips indices -- `dynamicallyObservable:
    false` (C51), `frameEmissionUnsupported` for this JVM's bytecode shape, or
    the whole tier when `ax.tier1.enabled=false`. A skipped index is never
    written by anything, so its bit is permanently zero.

        probes                     -> liveness (a set bit means it ran)
        probesInstalled & ~probes  -> the ONLY death evidence
        ~probesInstalled           -> SILENCE; not evidence of anything

    ``None`` means the AGENT SAID NOTHING (a pre-#18 producer). That is a
    different fact from an all-zero mask, which is a positive report that this
    JVM installed no probe for the class, and the two must never collapse:
    absent leaves the older behaviour untouched, all-zero withdraws every
    index from the death argument.
    """

    cls: str
    schema_hash: str
    probes: bytes
    probes_installed: bytes | None = None


#: `Tier2Record.error_types_source` -- WHERE the exception-class breakdown came
#: from. Three values, never collapsed, because "no types were sent" and "types
#: were sent and there were none" are different facts and only one of them
#: licenses saying "this method threw nothing of note".
ERRORS_BY_CLASS = "errorsByClass"
"""Contract v4: ids resolved against the window's own `errorClasses` table."""
ERROR_TYPES_LEGACY = "errorTypes"
"""The pre-v4 shape: names already spelled out on the wire."""
ERROR_TYPES_UNAVAILABLE = "unavailable"
"""No breakdown of any kind arrived. `errors` may still be non-zero."""


@dataclass(frozen=True, slots=True)
class Tier2Record:
    """One entry of CONTRACTS 2 `tier2[]`.

    `buckets` are raw log-linear counts. The agent NEVER sends a percentile
    (CONTRACTS 2 / C31); percentiles are derived server-side.

    BUG #24: `error_types` holds exception class NAMES, never the window-local
    ids the wire carries. `errorClasses` ids are valid only inside the window
    that shipped them -- id 1 in one window is unrelated to id 1 in the next --
    so `decode.py` resolves them at the point of decode and nothing downstream
    ever sees an id. Storing an id would be storing a number whose meaning was
    thrown away sixty seconds later.
    """

    cls: str
    idx: int
    calls: int
    errors: int
    error_types: Mapping[str, int]
    buckets: tuple[int, ...]
    bucket_scheme: str
    #: One of the three `ERROR_TYPES_*` constants above. `unavailable` with
    #: `errors > 0` is LEGAL (CONTRACTS 2 v4: an older agent, or the id table
    #: unavailable) and must be reported as "types unavailable", never as zero
    #: types.
    error_types_source: str = ERROR_TYPES_UNAVAILABLE
    #: Errors counted under an `errorsByClass` id that the window's own
    #: `errorClasses` table did not name. Reported as unattributed and counted
    #: here; NEVER given an invented class name.
    unresolved_id_errors: int = 0

    @property
    def attributed_errors(self) -> int:
        """Errors we can name a class for."""
        return sum(self.error_types.values())

    @property
    def unattributed_errors(self) -> int:
        """`errors - sum(errorsByClass)` -- legal, and never reconciled.

        CONTRACTS 2 v4: the agent's table holds 254 distinct classes, id 255
        is the overflow bucket, and `errors` is incremented unconditionally. So
        the sum can legitimately fall short, and the remainder is reported as
        UNATTRIBUTED. Inventing a class to close the gap, or scaling the named
        counts up to match, would both fabricate data.

        Clamped at zero: a sum LARGER than `errors` is a broken producer, and
        a negative "unattributed" would be a nonsense number to publish.
        """
        return max(0, self.errors - self.attributed_errors)

    @property
    def error_types_available(self) -> bool:
        """Did any usable breakdown arrive at all?"""
        return self.error_types_source != ERROR_TYPES_UNAVAILABLE


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
    #: CONTRACTS 2 v3 `edges[]`, already validated against the manifest.
    edges: tuple[EdgeRecord, ...] = ()
    #: CONTRACTS 2: "An absent key and an empty graph are different facts."
    #: False => this agent said nothing about edges (a v2 producer, or the key
    #: was omitted). True => the agent reported the tier, possibly as empty.
    edges_present: bool = False
    #: C50.1 -- explicit production classification of the reporting JVM.
    #: FAIL CLOSED: absent classification means this window is NOT usable as
    #: evidence of liveness.
    #:
    #: BUG #22b: such a window is now STORED AND MARKED rather than rejected.
    #: The fail-closed default is untouched -- it defaults to `False` here and
    #: the collector only ever sets it from an allowlisted label -- but the
    #: consequence moved from "no row exists" to "this row is evidence for
    #: nothing", which is where the safety property actually lives.
    environment: str = "unclassified"
    production: bool = False
    #: CONTRACTS 2 `agentHealth.livenessEvidence`, as CONCLUDED by the
    #: collector: `production` AND the agent did not explicitly say false.
    #: Defaults to True because `production` (default False) is the
    #: fail-closed half; a hand-constructed window is therefore still
    #: unusable as evidence until someone sets `production` on purpose.
    liveness_evidence: bool = True
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

    @property
    def usable_as_life_evidence(self) -> bool:
        """C50 -- may anything in this window prove a method ALIVE?

        This is the gate bug #22b's store-and-mark rests on: a non-production
        window is persisted, but nothing in it may merge into the coverage
        bitset, the installed-probe mask, the loaded-class table or the edge
        graph, because every one of those is read as evidence.

        `degraded` is deliberately absent (CONTRACTS 2): a degraded agent
        drops observations, it never invents them, so its set probes are still
        trustworthy proof of life.
        """
        return self.production and self.liveness_evidence and not self.test_tainted


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
    #: CONTRACTS 2 `agentHealth.livenessEvidence`, as persisted. Defaults True
    #: for the same reason as on `IngestWindow`: `production` is the
    #: fail-closed half of the pair.
    liveness_evidence: bool = True

    @property
    def duration_seconds(self) -> float:
        return (self.end - self.start).total_seconds()

    @property
    def usable_as_death_evidence(self) -> bool:
        """CONTRACTS 2 + C50: degraded, non-production or test-tainted windows
        can never contribute to a death argument.

        Bug #22b stores non-production windows instead of refusing them, so
        THIS is now the clause doing the work the 403 used to do. It is
        deliberately unchanged in substance -- `production` still has to be
        true -- and `liveness_evidence` is ANDed in so an agent that says
        `livenessEvidence: false` on an allowlisted label is still believed.
        """
        return (
            not self.degraded
            and self.production
            and self.liveness_evidence
            and not self.test_tainted
        )

    @property
    def usable_as_life_evidence(self) -> bool:
        """C50 -- may anything in this window prove a method ALIVE?

        Same clause minus `degraded`, per CONTRACTS 2: a degraded agent drops
        observations, it never invents them.
        """
        return self.production and self.liveness_evidence and not self.test_tainted

    @property
    def non_production(self) -> bool:
        """The bug #22b bucket: stored, counted, evidence for nothing."""
        return not self.production
