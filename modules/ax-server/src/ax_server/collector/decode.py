"""Parse and validate a CONTRACTS 2 body.

Strict about the frozen fields, tolerant of additive ones. Anything that would
cause us to silently mis-attribute coverage is a hard reject.
"""

from collections.abc import Mapping, Sequence
from typing import Any

from ax_server import SCHEMA_VERSION, SUPPORTED_SCHEMA_VERSIONS
from ax_server.collector.buckets import BUCKET_SCHEME
from ax_server.collector.classification import EnvironmentPolicy
from ax_server.collector.errors import IngestRejected, RejectReason
from ax_server.collector.health import IngestHealth
from ax_server.collector.known_methods import UNCHECKED, KnownMethods
from ax_server.collector.testrunner import TestRunnerDetector
from ax_server.store.bitset import BitsetDecodeError, decode_b64
from ax_server.store.models import (
    AgentHealth,
    CoverageRecord,
    EdgeHealth,
    EdgeRecord,
    IngestWindow,
    Tier2Record,
)

__all__ = ["decode_window"]


def _require(payload: Mapping[str, Any], key: str) -> Any:
    if key not in payload:
        raise IngestRejected(RejectReason.MISSING_FIELD, f"missing required field {key!r}")
    return payload[key]


def _as_str(value: Any, key: str) -> str:
    if not isinstance(value, str) or not value:
        raise IngestRejected(RejectReason.BAD_FIELD, f"{key} must be a non-empty string")
    return value


def _as_int(value: Any, key: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise IngestRejected(RejectReason.BAD_FIELD, f"{key} must be an integer")
    return value


def _counter(raw: Mapping[str, Any], key: str) -> int:
    """A v3 `agentHealth.edges*` counter. Absent or junk => 0.

    These are raw cumulative counts. A negative one is a broken agent, not a
    fact, so it is refused rather than stored: a negative `edgesDropped` would
    make the lossiness of the graph look smaller than it is.
    """
    value = raw.get(key)
    if value is None:
        return 0
    if isinstance(value, bool) or not isinstance(value, int):
        raise IngestRejected(RejectReason.BAD_FIELD, f"agentHealth.{key} must be an integer")
    if value < 0:
        raise IngestRejected(RejectReason.BAD_FIELD, f"agentHealth.{key} must not be negative")
    return value


def _edge_health(raw: Mapping[str, Any]) -> EdgeHealth:
    """CONTRACTS 2 v3 `agentHealth.edges*`.

    `edgesSampleRate` must be a power of two (CONTRACTS 2). We check it
    because the rate is the divisor for every count in `edges[]`: a bogus rate
    silently rescales the entire graph, and a wrong scale on a number that is
    already only relative is unrecoverable after the fact.
    """
    rate = _counter(raw, "edgesSampleRate")
    if rate and (rate & (rate - 1)):
        raise IngestRejected(
            RejectReason.BAD_FIELD,
            f"agentHealth.edgesSampleRate {rate} is not a power of two (CONTRACTS 2); "
            "the rate is the divisor for every count in edges[] and cannot be guessed",
        )
    return EdgeHealth(
        enabled=bool(raw.get("edgesEnabled", False)),
        sample_rate=rate,
        sampled_roots=_counter(raw, "edgesSampledRoots"),
        recorded=_counter(raw, "edgesRecorded"),
        dropped=_counter(raw, "edgesDropped"),
        truncated_depth=_counter(raw, "edgesTruncatedDepth"),
        truncated_root=_counter(raw, "edgesTruncatedRoot"),
        truncated_distinct=_counter(raw, "edgesTruncatedDistinct"),
        tier_failures=_counter(raw, "edgeTierFailures"),
        traces_reaped=_counter(raw, "edgeTracesReaped"),
    )


def _strip_mask_missing(raw: Mapping[str, Any]) -> int:
    """CONTRACTS 2 `agentHealth.stripMaskMissing` (bug #18).

    How many times this JVM could not determine which probe indices it had
    actually installed. When it cannot, the agent ships an ALL-ZERO
    `probesInstalled` mask -- losing candidates, never inventing them -- so
    this counter is the explanation for candidates that went missing, and is
    counted rather than silently dropped.

    A bare boolean is accepted and folded to 0/1: an agent that reports the
    condition as a flag is still telling us something we must not throw away,
    and refusing the window would discard the coverage bits with it.
    """
    value = raw.get("stripMaskMissing")
    if value is None:
        return 0
    if isinstance(value, bool):
        return int(value)
    if not isinstance(value, int):
        raise IngestRejected(
            RejectReason.BAD_FIELD, "agentHealth.stripMaskMissing must be an integer"
        )
    if value < 0:
        raise IngestRejected(
            RejectReason.BAD_FIELD, "agentHealth.stripMaskMissing must not be negative"
        )
    return value


def _agent_health(payload: Mapping[str, Any]) -> AgentHealth:
    # CONTRACTS 2: "`agentHealth` is **mandatory**." A window without it has no
    # way to declare itself degraded, so we cannot tell trustworthy silence
    # from a broken agent -- and silence is the entire signal here.
    raw = payload.get("agentHealth")
    if not isinstance(raw, Mapping):
        raise IngestRejected(
            RejectReason.MISSING_AGENT_HEALTH,
            "agentHealth is mandatory (CONTRACTS 2) and must be an object",
        )
    skipped_raw = raw.get("classesSkipped") or {}
    if not isinstance(skipped_raw, Mapping):
        raise IngestRejected(RejectReason.BAD_FIELD, "agentHealth.classesSkipped must be an object")
    return AgentHealth(
        edges=_edge_health(raw),
        transform_failures=int(raw.get("transformFailures", 0) or 0),
        classes_skipped={str(k): int(v) for k, v in skipped_raw.items()},
        ring_dropped=int(raw.get("ringDropped", 0) or 0),
        clock_ns=int(raw.get("clockNs", 0) or 0),
        clock_degraded=bool(raw.get("clockDegraded", False)),
        degraded=bool(raw.get("degraded", False)),
        strip_mask_missing=_strip_mask_missing(raw),
        raw=dict(raw),
    )


def _coverage(
    raw: Any, detector: TestRunnerDetector, health: IngestHealth
) -> tuple[tuple[CoverageRecord, ...], int]:
    if raw is None:
        return (), 0
    if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)):
        raise IngestRejected(RejectReason.BAD_FIELD, "coverage must be an array")
    out: list[CoverageRecord] = []
    discarded = 0
    for entry in raw:
        if not isinstance(entry, Mapping):
            raise IngestRejected(RejectReason.BAD_FIELD, "coverage[] entries must be objects")
        cls = _as_str(entry.get("class"), "coverage[].class")
        schema_hash = _as_str(entry.get("schemaHash"), "coverage[].schemaHash")
        frames = entry.get("frames") or ()
        if not isinstance(frames, Sequence) or isinstance(frames, (str, bytes)):
            raise IngestRejected(RejectReason.BAD_FIELD, "coverage[].frames must be an array")
        # C50.3: discard any coverage record carrying a test-runner frame.
        taint = detector.record_tainted(cls, [str(f) for f in frames])
        if taint.tainted:
            discarded += 1
            health.count_partial(RejectReason.TEST_RUNNER_RECORD, cls)
            continue
        try:
            probes = decode_b64(entry.get("probes", ""))
        except BitsetDecodeError as exc:
            raise IngestRejected(RejectReason.BAD_PROBE_BITSET, str(exc)) from exc
        # Bug #18. ABSENT is tolerated (a pre-#18 agent) and is NOT the same
        # fact as an all-zero mask, so it stays `None` all the way down rather
        # than being normalised to empty bytes here.
        # NOTE the live agent (ax-agent `Batch.java`) always WRITES this key
        # and writes `""` when it could not determine the mask -- which
        # decodes to an all-zero mask, i.e. "this JVM installed nothing here".
        # That is the intended bias (lose a candidate, never invent one), so
        # `""` must NOT be folded into the absent case; only a genuinely
        # missing key means "a pre-#18 agent said nothing".
        installed_raw = entry.get("probesInstalled")
        if installed_raw is None:
            installed: bytes | None = None
        else:
            try:
                installed = decode_b64(installed_raw)
            except BitsetDecodeError as exc:
                # Refused, not defaulted. A mask we cannot read must never be
                # replaced by a guess: guessing "all installed" re-creates the
                # bug, and guessing "none installed" silently discards every
                # candidate in the window without saying so.
                raise IngestRejected(
                    RejectReason.BAD_PROBE_BITSET, f"coverage[].probesInstalled: {exc}"
                ) from exc
        out.append(
            CoverageRecord(
                cls=cls,
                schema_hash=schema_hash,
                probes=probes,
                probes_installed=installed,
            )
        )
    return tuple(out), discarded


def _tier2(raw: Any) -> tuple[Tier2Record, ...]:
    if raw is None:
        return ()
    if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)):
        raise IngestRejected(RejectReason.BAD_FIELD, "tier2 must be an array")
    out: list[Tier2Record] = []
    for entry in raw:
        if not isinstance(entry, Mapping):
            raise IngestRejected(RejectReason.BAD_FIELD, "tier2[] entries must be objects")
        scheme = str(entry.get("bucketScheme", BUCKET_SCHEME))
        if scheme != BUCKET_SCHEME:
            raise IngestRejected(
                RejectReason.UNKNOWN_BUCKET_SCHEME,
                f"tier2[].bucketScheme {scheme!r} is not {BUCKET_SCHEME!r}; refusing to guess",
            )
        buckets_raw = entry.get("buckets") or []
        if not isinstance(buckets_raw, Sequence) or isinstance(buckets_raw, (str, bytes)):
            raise IngestRejected(RejectReason.BAD_FIELD, "tier2[].buckets must be an array")
        if any(k in entry for k in ("p50", "p90", "p99", "percentiles")):
            # CONTRACTS 2 / C31: "Never send a percentile." Percentiles of a
            # union are not a function of per-pod percentiles, so accepting one
            # would let a wrong number in through the front door.
            raise IngestRejected(
                RejectReason.BAD_FIELD,
                "tier2[] carries a percentile; the agent must send buckets only (CONTRACTS 2)",
            )
        error_types_raw = entry.get("errorTypes") or {}
        if not isinstance(error_types_raw, Mapping):
            raise IngestRejected(RejectReason.BAD_FIELD, "tier2[].errorTypes must be an object")
        out.append(
            Tier2Record(
                cls=_as_str(entry.get("class"), "tier2[].class"),
                idx=_as_int(entry.get("idx"), "tier2[].idx"),
                calls=int(entry.get("calls", 0) or 0),
                errors=int(entry.get("errors", 0) or 0),
                error_types={str(k): int(v) for k, v in error_types_raw.items()},
                buckets=tuple(int(b) for b in buckets_raw),
                bucket_scheme=scheme,
            )
        )
    return tuple(out)


def _edges(
    raw: Any,
    *,
    build_sha: str,
    known: KnownMethods,
) -> tuple[tuple[EdgeRecord, ...], bool, bool]:
    """Decode CONTRACTS 2 v3 `edges[]`.

    Returns `(records, present, verified)`.

    * `present` distinguishes an ABSENT key from an EMPTY array. CONTRACTS 2:
      "An absent key and an empty graph are different facts." A v2 agent sends
      neither and must keep working; an agent with the tier off sends `[]`.
    * `verified` says whether the manifest check actually ran. A collector with
      no manifest for this build has not proved the endpoints are good, it has
      failed to look, and that gets counted rather than assumed.

    An entry naming a `(class, idx)` the manifest does not declare rejects the
    WHOLE payload. A dangling edge is not a partial loss like a mismatched
    coverage record: it is a reference into an identity space that does not
    exist, which means either the agent is running against a different build
    than it claims or the manifest is not the one that was shipped. Either way
    the rest of this window's identities are suspect too.
    """
    if raw is None:
        return (), False, False
    if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)):
        raise IngestRejected(RejectReason.BAD_FIELD, "edges must be an array")

    can_check = known.knows_build(build_sha)
    out: list[EdgeRecord] = []
    for entry in raw:
        if not isinstance(entry, Mapping):
            raise IngestRejected(RejectReason.BAD_FIELD, "edges[] entries must be objects")
        if any(k in entry for k in ("rate", "ratio", "percentage", "callsPerSecond")):
            # C31 again: the agent sends raw counts, never a derived rate.
            raise IngestRejected(
                RejectReason.BAD_FIELD,
                "edges[] carries a derived rate; the agent sends raw counts only (C31)",
            )
        from_cls = _as_str(entry.get("fromClass"), "edges[].fromClass")
        to_cls = _as_str(entry.get("toClass"), "edges[].toClass")
        from_idx = _as_int(entry.get("fromIdx"), "edges[].fromIdx")
        to_idx = _as_int(entry.get("toIdx"), "edges[].toIdx")
        count = _as_int(entry.get("count", 0), "edges[].count")
        if from_idx < 0 or to_idx < 0:
            raise IngestRejected(
                RejectReason.BAD_FIELD,
                "edges[] idx is a build-time manifest index and cannot be negative",
            )
        if count < 0:
            raise IngestRejected(
                RejectReason.BAD_FIELD, "edges[].count is a raw observation count and cannot "
                "be negative",
            )
        if can_check:
            for role, cls, idx in (("from", from_cls, from_idx), ("to", to_cls, to_idx)):
                if not known.has_method(build_sha, cls, idx):
                    raise IngestRejected(
                        RejectReason.UNKNOWN_EDGE_ENDPOINT,
                        f"edges[].{role} references {cls}#{idx}, which is absent from the "
                        f"known manifest ({known.describe()}); refusing to store a dangling "
                        "edge -- the identity would never resolve for any reader",
                    )
        out.append(
            EdgeRecord(
                caller_cls=from_cls,
                caller_idx=from_idx,
                callee_cls=to_cls,
                callee_idx=to_idx,
                count=count,
            )
        )
    return tuple(out), True, can_check


def decode_window(
    payload: Mapping[str, Any],
    *,
    policy: EnvironmentPolicy,
    detector: TestRunnerDetector,
    health: IngestHealth,
    known: KnownMethods = UNCHECKED,
) -> tuple[IngestWindow, int]:
    """Validate a body and build an `IngestWindow`.

    Returns (window, discarded_test_records).

    Raises:
        IngestRejected: on any violation of CONTRACTS 2 or the C50 gate.
    """
    if not isinstance(payload, Mapping):
        raise IngestRejected(RejectReason.MALFORMED_BODY, "body must be a JSON object")

    version = _as_int(_require(payload, "schemaVersion"), "schemaVersion")
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        # LOUD, per the brief and CONTRACTS 1. Accepting an unknown version
        # means accepting an unknown probe-index assignment (A14 defect 2).
        raise IngestRejected(
            RejectReason.SCHEMA_VERSION,
            f"schemaVersion {version} not in {sorted(SUPPORTED_SCHEMA_VERSIONS)}; refusing to merge",
        )

    build_sha = _as_str(_require(payload, "buildSha"), "buildSha")
    artifact = _as_str(_require(payload, "artifact"), "artifact")
    instance_id = _as_str(_require(payload, "instanceId"), "instanceId")
    start_ms = _as_int(_require(payload, "windowStartMs"), "windowStartMs")
    end_ms = _as_int(_require(payload, "windowEndMs"), "windowEndMs")
    if end_ms < start_ms:
        raise IngestRejected(RejectReason.BAD_FIELD, "windowEndMs is before windowStartMs")

    agent_health = _agent_health(payload)

    classification = policy.classify(payload)
    if not classification.production:
        raise IngestRejected(RejectReason.NOT_PRODUCTION, classification.reason, status=403)

    classes_loaded_raw = payload.get("classesLoaded") or []
    if not isinstance(classes_loaded_raw, Sequence) or isinstance(classes_loaded_raw, (str, bytes)):
        raise IngestRejected(RejectReason.BAD_FIELD, "classesLoaded must be an array")
    classes_loaded = tuple(_as_str(c, "classesLoaded[]") for c in classes_loaded_raw)

    # C50.3 at window level: a test runner anywhere in this JVM taints the
    # whole window. We do NOT half-trust it.
    taint = detector.scan(classes_loaded)
    if taint.tainted:
        raise IngestRejected(
            RejectReason.TEST_RUNNER_WINDOW,
            "test-runner frames present in classesLoaded "
            f"({', '.join(taint.markers[:3])}); test runs are not a liveness signal (C50)",
            status=403,
        )

    coverage, discarded = _coverage(payload.get("coverage"), detector, health)
    tier2 = _tier2(payload.get("tier2"))
    edges, edges_present, edges_verified = _edges(
        payload.get("edges"), build_sha=build_sha, known=known
    )
    if edges and not agent_health.edges.sample_rate:
        # A count with no declared rate is not a small number, it is an
        # unscaled one. Storing it would put an uninterpretable figure into
        # the same column as interpretable ones.
        raise IngestRejected(
            RejectReason.BAD_FIELD,
            "edges[] is non-empty but agentHealth.edgesSampleRate is absent; the counts "
            "are uninterpretable without it (CONTRACTS 2 v3)",
        )

    window = IngestWindow(
        schema_version=version,
        build_sha=build_sha,
        artifact=artifact,
        instance_id=instance_id,
        window_start_ms=start_ms,
        window_end_ms=end_ms,
        agent_health=agent_health,
        classes_loaded=classes_loaded,
        coverage=coverage,
        tier2=tier2,
        edges=edges,
        edges_present=edges_present,
        environment=classification.environment,
        production=classification.production,
        test_tainted=False,
        test_markers=(),
    )
    return window, discarded
