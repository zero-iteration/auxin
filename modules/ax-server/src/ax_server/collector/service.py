"""The ingest pipeline: bytes -> validated window -> OR-merged coverage."""

import gzip
import json
import logging
import zlib
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from ax_server.collector.buckets import BUCKET_SCHEME, percentiles
from ax_server.collector.classification import EnvironmentPolicy
from ax_server.collector.decode import decode_window
from ax_server.collector.errors import IngestRejected, RejectReason
from ax_server.collector.health import IngestHealth
from ax_server.collector.testrunner import TestRunnerDetector
from ax_server.store.bitset import newly_set
from ax_server.store.errors import SchemaMismatch
from ax_server.store.models import IngestWindow
from ax_server.store.port import IngestAudit, Store, WindowAttribution

__all__ = ["CollectorService", "IngestResult", "MAX_BODY_BYTES"]

log = logging.getLogger("ax.collector")

#: Hard cap on a decompressed body. A 188MB JaCoCo exec file consuming >2GB is
#: a documented real precedent (A14 defect 3); we refuse rather than OOM.
MAX_BODY_BYTES = 64 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class IngestResult:
    accepted: bool
    build_sha: str
    artifact: str
    instance_id: str
    classes_merged: int
    probes_newly_set: int
    schema_mismatches: tuple[str, ...]
    discarded_test_records: int
    degraded: bool
    tier2_percentiles: Mapping[str, Mapping[str, int | None]]

    def to_json(self) -> dict[str, Any]:
        return {
            "accepted": self.accepted,
            "buildSha": self.build_sha,
            "artifact": self.artifact,
            "instanceId": self.instance_id,
            "classesMerged": self.classes_merged,
            "probesNewlySet": self.probes_newly_set,
            "schemaMismatches": list(self.schema_mismatches),
            "discardedTestRecords": self.discarded_test_records,
            "degraded": self.degraded,
            "tier2Percentiles": {k: dict(v) for k, v in self.tier2_percentiles.items()},
        }


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


class CollectorService:
    """`POST /v1/ingest` without the HTTP.

    Kept transport-free so the whole ingest path is unit-testable and so the
    HTTP layer stays a thin shell.
    """

    def __init__(
        self,
        store: Store,
        *,
        policy: EnvironmentPolicy | None = None,
        detector: TestRunnerDetector | None = None,
        health: IngestHealth | None = None,
        clock: Callable[[], datetime] = _utcnow,
        max_body_bytes: int = MAX_BODY_BYTES,
    ) -> None:
        self.store = store
        self.policy = policy or EnvironmentPolicy()
        self.detector = detector or TestRunnerDetector()
        self.health = health or IngestHealth()
        self._clock = clock
        self._max_body = max_body_bytes

    # -- entry points ----------------------------------------------------

    def ingest_bytes(self, body: bytes, *, content_encoding: str | None = None) -> IngestResult:
        """Gzip-aware. CONTRACTS 2 says the body is gzipped; we also accept
        plain JSON so a human can curl the endpoint."""
        raw_len = len(body)
        payload_bytes = self._maybe_gunzip(body, content_encoding)
        self.health.observe_bytes(raw_len, len(payload_bytes))
        if len(payload_bytes) > self._max_body:
            self._reject(
                RejectReason.PAYLOAD_TOO_LARGE,
                f"decompressed body is {len(payload_bytes)} bytes (cap {self._max_body})",
                None,
                status=413,
            )
        try:
            payload = json.loads(payload_bytes)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            self._reject(RejectReason.MALFORMED_BODY, f"body is not valid JSON: {exc}", None)
        return self.ingest(payload)

    def ingest(self, payload: Mapping[str, Any]) -> IngestResult:
        try:
            window, discarded = decode_window(
                payload, policy=self.policy, detector=self.detector, health=self.health
            )
        except IngestRejected as exc:
            self._log_and_count(exc, payload)
            raise
        return self._apply(window, discarded)

    # -- internals -------------------------------------------------------

    def _maybe_gunzip(self, body: bytes, content_encoding: str | None) -> bytes:
        looks_gzipped = body[:2] == b"\x1f\x8b"
        declared = (content_encoding or "").lower().strip() == "gzip"
        if not (looks_gzipped or declared):
            return body
        try:
            return gzip.decompress(body)
        except (OSError, EOFError, zlib.error) as exc:
            self._reject(RejectReason.BAD_GZIP, f"could not gunzip body: {exc}", None)
        raise AssertionError("unreachable")

    def _apply(self, window: IngestWindow, discarded: int) -> IngestResult:
        when = window.end
        self.store.record_window(window)

        mismatches: list[str] = []
        merged = 0
        fresh_bits = 0
        attribution = (
            self.store.attribute_to(when)
            if isinstance(self.store, WindowAttribution)
            else _null_context()
        )
        with attribution:
            for record in window.coverage:
                existing = self.store.coverage(window.build_sha, record.cls) or b""
                try:
                    self.store.merge_coverage(
                        window.build_sha, record.cls, record.schema_hash, record.probes
                    )
                except SchemaMismatch as exc:
                    # LOUD. Drop just this class; the rest of the window is
                    # still good data and throwing it away would lose real
                    # liveness evidence.
                    log.error(
                        "SCHEMA MISMATCH build=%s class=%s stored=%s incoming=%s "
                        "instance=%s -- record dropped, NOT merged, NOT zeroed",
                        window.build_sha, record.cls, exc.expected, exc.actual,
                        window.instance_id,
                    )
                    mismatches.append(record.cls)
                    self.health.count_partial(RejectReason.SCHEMA_HASH_MISMATCH, record.cls)
                    self._audit(
                        RejectReason.SCHEMA_HASH_MISMATCH,
                        window,
                        f"{record.cls}: stored={exc.expected} incoming={exc.actual}",
                    )
                    continue
                merged += 1
                fresh_bits += len(newly_set(existing, record.probes))

        if window.agent_health.degraded:
            log.warning(
                "degraded window accepted build=%s instance=%s ringDropped=%d "
                "transformFailures=%d -- usable as LIFE evidence only, never as death evidence",
                window.build_sha, window.instance_id,
                window.agent_health.ring_dropped, window.agent_health.transform_failures,
            )

        self.health.accept(
            degraded=window.agent_health.degraded,
            classes_merged=merged,
            classes_loaded=len(window.classes_loaded),
            probes_newly_set=fresh_bits,
            tier2_records=len(window.tier2),
            discarded_test_records=discarded,
        )

        pcts = {
            f"{rec.cls}#{rec.idx}": percentiles(rec.buckets, scheme=BUCKET_SCHEME)
            for rec in window.tier2
        }
        return IngestResult(
            accepted=True,
            build_sha=window.build_sha,
            artifact=window.artifact,
            instance_id=window.instance_id,
            classes_merged=merged,
            probes_newly_set=fresh_bits,
            schema_mismatches=tuple(mismatches),
            discarded_test_records=discarded,
            degraded=window.agent_health.degraded,
            tier2_percentiles=pcts,
        )

    def _reject(
        self,
        reason: str,
        detail: str,
        payload: Mapping[str, Any] | None,
        *,
        status: int = 422,
    ) -> None:
        exc = IngestRejected(reason, detail, status=status)
        self._log_and_count(exc, payload)
        raise exc

    def _log_and_count(self, exc: IngestRejected, payload: Mapping[str, Any] | None) -> None:
        log.error("INGEST REJECTED reason=%s detail=%s", exc.reason, exc.detail)
        self.health.reject(exc.reason)
        if isinstance(self.store, IngestAudit):
            data = payload if isinstance(payload, Mapping) else {}
            self.store.record_reject(
                reason=exc.reason,
                build_sha=_opt_str(data.get("buildSha")),
                artifact=_opt_str(data.get("artifact")),
                instance_id=_opt_str(data.get("instanceId")),
                detail=exc.detail,
                when=self._clock(),
            )

    def _audit(self, reason: str, window: IngestWindow, detail: str) -> None:
        if isinstance(self.store, IngestAudit):
            self.store.record_reject(
                reason=reason,
                build_sha=window.build_sha,
                artifact=window.artifact,
                instance_id=window.instance_id,
                detail=detail,
                when=self._clock(),
            )


def _opt_str(value: Any) -> str | None:
    return value if isinstance(value, str) else None


class _null_context:
    def __enter__(self) -> None:
        return None

    def __exit__(self, *exc: object) -> None:
        return None
