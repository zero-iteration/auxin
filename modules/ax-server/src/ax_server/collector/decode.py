"""Parse and validate a CONTRACTS 2 body.

Strict about the frozen fields, tolerant of additive ones. Anything that would
cause us to silently mis-attribute coverage is a hard reject.
"""

from collections.abc import Mapping, Sequence
from typing import Any

from ax_server import SCHEMA_VERSION
from ax_server.collector.buckets import BUCKET_SCHEME
from ax_server.collector.classification import EnvironmentPolicy
from ax_server.collector.errors import IngestRejected, RejectReason
from ax_server.collector.health import IngestHealth
from ax_server.collector.testrunner import TestRunnerDetector
from ax_server.store.bitset import BitsetDecodeError, decode_b64
from ax_server.store.models import AgentHealth, CoverageRecord, IngestWindow, Tier2Record

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
        transform_failures=int(raw.get("transformFailures", 0) or 0),
        classes_skipped={str(k): int(v) for k, v in skipped_raw.items()},
        ring_dropped=int(raw.get("ringDropped", 0) or 0),
        clock_ns=int(raw.get("clockNs", 0) or 0),
        clock_degraded=bool(raw.get("clockDegraded", False)),
        degraded=bool(raw.get("degraded", False)),
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
        out.append(CoverageRecord(cls=cls, schema_hash=schema_hash, probes=probes))
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


def decode_window(
    payload: Mapping[str, Any],
    *,
    policy: EnvironmentPolicy,
    detector: TestRunnerDetector,
    health: IngestHealth,
) -> tuple[IngestWindow, int]:
    """Validate a body and build an `IngestWindow`.

    Returns (window, discarded_test_records).

    Raises:
        IngestRejected: on any violation of CONTRACTS 2 or the C50 gate.
    """
    if not isinstance(payload, Mapping):
        raise IngestRejected(RejectReason.MALFORMED_BODY, "body must be a JSON object")

    version = _as_int(_require(payload, "schemaVersion"), "schemaVersion")
    if version != SCHEMA_VERSION:
        # LOUD, per the brief and CONTRACTS 1. Accepting an unknown version
        # means accepting an unknown probe-index assignment (A14 defect 2).
        raise IngestRejected(
            RejectReason.SCHEMA_VERSION,
            f"schemaVersion {version} != {SCHEMA_VERSION}; refusing to merge",
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
        environment=classification.environment,
        production=classification.production,
        test_tainted=False,
        test_markers=(),
    )
    return window, discarded
