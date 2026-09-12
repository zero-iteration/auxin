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
from ax_server.collector.classification import NON_PRODUCTION_MEANING, EnvironmentPolicy
from ax_server.collector.decode import decode_error_classes, decode_window
from ax_server.collector.errors import IngestRejected, RejectReason
from ax_server.collector.health import ERROR_ATTRIBUTION_NOTE, IngestHealth
from ax_server.collector.known_methods import UNCHECKED, KnownMethods
from ax_server.collector.testrunner import TestRunnerDetector
from ax_server.store.bitset import and_not, newly_set, popcount
from ax_server.store.errors import SchemaMismatch
from ax_server.store.models import IngestWindow
from ax_server.store.port import (
    EdgeStore,
    IngestAudit,
    ProbeInstallStore,
    Store,
    WindowAttribution,
)

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
    #: SCOPE-v3. `edge_sampled_observations` is a sum of RAW observations in
    #: sampled traces, NOT a call total: divide nothing, multiply by
    #: `edges_sample_rate` for an estimate, and never quote it as exact.
    edges_merged: int = 0
    edge_sampled_observations: int = 0
    edges_sample_rate: int | None = None
    edges_enabled: bool = False
    edges_reported: bool = False
    edge_endpoints_verified: bool = False
    #: Bug #18. `install_masks_merged` counts coverage records whose
    #: `probesInstalled` mask was OR-merged; `records_without_install_mask`
    #: counts records from a pre-#18 agent that carried none. An absent mask
    #: is NOT an all-zero one, so the two are never added together.
    install_masks_merged: int = 0
    records_without_install_mask: int = 0
    install_mask_inconsistencies: int = 0
    #: Masks that were reported but NOT stored -- the adapter has no
    #: `ProbeInstallStore`, or it refused them. The #18 gate is not running
    #: for those classes, which is a different fact from "no mask was sent".
    install_masks_dropped: int = 0
    strip_mask_missing: int = 0
    # -- BUG #22b: the C50 evidence gate, as a result and not a 403 ------
    #: The label this JVM reported, and whether it cleared the allowlist. A
    #: non-production window is ACCEPTED (`accepted=True`) and STORED -- it is
    #: simply evidence for nothing, and these fields are how the user finds
    #: that out from the ingest response itself.
    environment: str = "production"
    production: bool = True
    liveness_evidence: bool = True
    #: What was withheld because the window is not evidence. Every one of
    #: these would have been a merge into something the analysis reads.
    coverage_records_withheld: int = 0
    probe_bits_withheld: int = 0
    classes_loaded_withheld: int = 0
    edges_withheld: int = 0
    # -- BUG #24: exception classes, per window --------------------------
    #: Size of the window-local `errorClasses` table, and the attribution
    #: split. `errors_unattributed` is legal and is never reconciled.
    error_classes_named: int = 0
    errors_attributed: int = 0
    errors_unattributed: int = 0
    error_class_ids_unresolved: int = 0
    tier2_records_without_error_types: int = 0

    def to_json(self) -> dict[str, Any]:
        return {
            "accepted": self.accepted,
            "buildSha": self.build_sha,
            "artifact": self.artifact,
            "instanceId": self.instance_id,
            "environment": self.environment,
            "production": self.production,
            "livenessEvidence": self.liveness_evidence,
            "nonProductionWithheld": {
                "coverageRecords": self.coverage_records_withheld,
                "probeBits": self.probe_bits_withheld,
                "classesLoaded": self.classes_loaded_withheld,
                "edges": self.edges_withheld,
            },
            "livenessEvidenceNote": (
                NON_PRODUCTION_MEANING
                if not self.liveness_evidence
                else "this window is production-classified and is usable as evidence"
            ),
            "errorClassesNamed": self.error_classes_named,
            "errorsAttributed": self.errors_attributed,
            "errorsUnattributed": self.errors_unattributed,
            "errorClassIdsUnresolved": self.error_class_ids_unresolved,
            "tier2RecordsWithoutErrorTypes": self.tier2_records_without_error_types,
            "errorAttributionNote": ERROR_ATTRIBUTION_NOTE,
            "classesMerged": self.classes_merged,
            "probesNewlySet": self.probes_newly_set,
            "schemaMismatches": list(self.schema_mismatches),
            "discardedTestRecords": self.discarded_test_records,
            "degraded": self.degraded,
            "tier2Percentiles": {k: dict(v) for k, v in self.tier2_percentiles.items()},
            "edgesMerged": self.edges_merged,
            "edgeSampledObservations": self.edge_sampled_observations,
            "edgesSampleRate": self.edges_sample_rate,
            "edgesEnabled": self.edges_enabled,
            "edgesReported": self.edges_reported,
            "edgeEndpointsVerified": self.edge_endpoints_verified,
            "edgeCountNote": (
                "edgeSampledObservations counts observations in SAMPLED traces, not "
                "calls; multiply by edgesSampleRate for an estimate and never present "
                "it as exact (CONTRACTS 2 v3)"
            ),
            "installMasksMerged": self.install_masks_merged,
            "recordsWithoutInstallMask": self.records_without_install_mask,
            "installMaskInconsistencies": self.install_mask_inconsistencies,
            "installMasksDropped": self.install_masks_dropped,
            "stripMaskMissing": self.strip_mask_missing,
            "installMaskNote": (
                "probesInstalled is the set of indices a probe was ACTUALLY installed at. "
                "probes => liveness; probesInstalled & ~probes => the only death "
                "evidence; ~probesInstalled => silence, never evidence of anything "
                "(bug #18)"
            ),
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
        known_methods: KnownMethods = UNCHECKED,
    ) -> None:
        self.store = store
        self.policy = policy or EnvironmentPolicy()
        self.detector = detector or TestRunnerDetector()
        self.health = health or IngestHealth()
        #: The manifest identity space `edges[]` endpoints are checked against.
        #: Defaults to "cannot check", which is counted, never assumed away.
        self.known_methods = known_methods
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
                payload,
                policy=self.policy,
                detector=self.detector,
                health=self.health,
                known=self.known_methods,
            )
        except IngestRejected as exc:
            self._log_and_count(exc, payload)
            raise
        # Re-read purely for the counter; `decode_window` already validated it,
        # so this cannot raise, and it keeps the table out of `IngestWindow`
        # where a window-local id has no business being persisted.
        named = len(decode_error_classes(payload))
        return self._apply(window, discarded, error_classes_named=named)

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

    def _apply(
        self, window: IngestWindow, discarded: int, *, error_classes_named: int = 0
    ) -> IngestResult:
        when = window.end
        self.store.record_window(window)

        # -- BUG #22b: the evidence gate, in ONE place --------------------
        #
        # A non-production window is PERSISTED above -- the user sees the row,
        # the counters and its tier-2 timings -- and then contributes to
        # nothing below. Every merge skipped here is a merge into something
        # the analysis reads as evidence:
        #
        #   merge_coverage          -> `observed` -> LIVE
        #   merge_probes_installed  -> whether an unset bit is death evidence
        #   class_loaded            -> removes the C10 `class-never-loaded` blocker
        #   record_edges            -> an observed inbound edge -> LIVE
        #
        # That last pair is why "just don't count it as liveness" is not
        # enough: `class_loaded` and the install mask make a DEAD_CANDIDATE
        # MORE likely, so a laptop could manufacture one. Withholding all four
        # is what makes storing the window safe in both directions.
        #
        # `classes_loaded` is withheld inside `SqliteStore.record_window`,
        # which is the only writer of that table; it is counted here.
        evidence = window.usable_as_life_evidence
        if not evidence:
            log.info(
                "NON-PRODUCTION window STORED build=%s instance=%s environment=%r "
                "livenessEvidence=false -- %d coverage record(s), %d probe bit(s), "
                "%d loaded class(es) and %d edge(s) withheld from evidence. %s",
                window.build_sha, window.instance_id, window.environment,
                len(window.coverage), sum(popcount(r.probes) for r in window.coverage),
                len(window.classes_loaded), len(window.edges), NON_PRODUCTION_MEANING,
            )

        mismatches: list[str] = []
        merged = 0
        fresh_bits = 0
        # Bug #18 counters. Kept apart from `merged` because a record that
        # carried no mask and one that reported an empty mask are different
        # facts and an operator has to be able to tell them apart.
        install_merged = 0
        install_absent = 0
        install_inconsistent = 0
        #: masks the store cannot hold (no `ProbeInstallStore`) ...
        install_unsupported = 0
        #: ... as distinct from masks refused by the store. Two different
        #: operator actions, so two counters and two log lines.
        install_dropped = 0
        attribution = (
            self.store.attribute_to(when)
            if isinstance(self.store, WindowAttribution)
            else _null_context()
        )
        with attribution:
            for record in window.coverage if evidence else ():
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

                # -- the installed-probe mask (bug #18) ------------------
                # Merged immediately after the coverage OR and under the same
                # window attribution: the two are indexed by the same
                # build-time probe index and checked against the same schema
                # hash, so storing one without the other would leave the gate
                # and the evidence it gates out of step. Only reached when the
                # coverage merge SUCCEEDED -- a class whose record was dropped
                # for a schema mismatch contributes no mask either.
                if record.probes_installed is None:
                    # A pre-#18 agent. NOT normalised to an all-zero mask:
                    # that would withdraw every index in the class from the
                    # death argument on the strength of a producer's age.
                    install_absent += 1
                    continue
                stray = popcount(and_not(record.probes, record.probes_installed))
                if stray:
                    # A probe that fired must have been installed, so the mask
                    # is stale or wrong. Loud, and counted -- but not fatal:
                    # the set bit is still good evidence of LIFE, and the mask
                    # is only ever used to WITHHOLD death evidence.
                    # `stripMaskMissing` is quoted in the same line because a
                    # non-zero value is the LIKELY CAUSE: the agent could not
                    # determine its mask and shipped an all-zero one, so an
                    # already-set probe now looks "uninstalled". Making the
                    # operator correlate two log lines to learn that is how a
                    # benign counter gets chased as a data bug.
                    log.warning(
                        "INSTALL MASK INCONSISTENT build=%s class=%s instance=%s -- %d "
                        "probe(s) are set at indices the mask says were never "
                        "instrumented (agentHealth.stripMaskMissing=%d); the set bits "
                        "remain valid evidence of life",
                        window.build_sha, record.cls, window.instance_id, stray,
                        window.agent_health.strip_mask_missing,
                    )
                    install_inconsistent += 1
                    self.health.count_partial(
                        RejectReason.PROBE_INSTALL_MASK_INCONSISTENT, record.cls
                    )
                if isinstance(self.store, ProbeInstallStore):
                    try:
                        self.store.merge_probes_installed(
                            window.build_sha,
                            record.cls,
                            record.schema_hash,
                            record.probes_installed,
                        )
                    except SchemaMismatch as exc:
                        # Unreachable while the two tables are written
                        # together, which they are -- but if they ever
                        # diverge, DROP THE MASK, never the window. Losing a
                        # mask costs candidates; failing the window costs the
                        # liveness evidence that was already merged above.
                        log.error(
                            "INSTALL MASK SCHEMA MISMATCH build=%s class=%s stored=%s "
                            "incoming=%s -- mask dropped, coverage kept",
                            window.build_sha, record.cls, exc.expected, exc.actual,
                        )
                        install_dropped += 1
                        self.health.count_partial(
                            RejectReason.SCHEMA_HASH_MISMATCH, record.cls
                        )
                    else:
                        install_merged += 1
                else:
                    install_unsupported += 1

        if install_unsupported:
            # Nothing is misattributed -- the analysis falls back to "no mask
            # reported", which is the pre-#18 behaviour -- but the gate is not
            # running, and that has to be visible rather than assumed away.
            log.warning(
                "store %s does not implement ProbeInstallStore; %d installed-probe "
                "mask(s) DISCARDED for build=%s instance=%s -- the bug #18 gate is "
                "NOT running for this build",
                type(self.store).__name__, install_unsupported,
                window.build_sha, window.instance_id,
            )
            self.health.count_partial(RejectReason.PROBE_INSTALL_MASK_DISCARDED)
        if window.agent_health.strip_mask_missing:
            log.warning(
                "agent could not determine its installed-probe mask %d time(s) "
                "build=%s instance=%s -- it shipped an ALL-ZERO probesInstalled, so "
                "candidates are LOST rather than invented (bug #18)",
                window.agent_health.strip_mask_missing,
                window.build_sha, window.instance_id,
            )

        # -- the SCOPE-v3 edge tier ------------------------------------
        # ADDITIVE, and deliberately not inside the coverage loop above: that
        # loop performs an idempotent OR of an accumulated bitset, this adds a
        # per-window delta. Two operations, two semantics, two code paths.
        edges_merged = 0
        edge_health = window.agent_health.edges
        if not evidence:
            # CONTRACTS 2 v3: an edge "is evidence of liveness only, and only
            # under the same C50 gate as everything else in the window". An
            # observed inbound edge short-circuits straight to LIVE, so a
            # laptop's edges must never reach the aggregate.
            pass
        elif isinstance(self.store, EdgeStore):
            edges_merged = self.store.record_edges(window)
        elif window.edges:
            log.warning(
                "store %s does not implement EdgeStore; %d edge record(s) DISCARDED "
                "for build=%s instance=%s",
                type(self.store).__name__, len(window.edges),
                window.build_sha, window.instance_id,
            )
        edge_observations = sum(e.count for e in window.edges) if evidence else 0
        verified = self.known_methods.knows_build(window.build_sha)
        if evidence and window.edges and not verified:
            log.warning(
                "edge endpoints NOT verified for build=%s (%s); a dangling (class, idx) "
                "would not have been caught",
                window.build_sha, self.known_methods.describe(),
            )
        if edge_health.lossy:
            # Not `degraded`: CONTRACTS 2 is explicit that a latched-off edge
            # tier leaves coverage and tier-2 in the same window valid.
            log.info(
                "edge tier lossy build=%s instance=%s dropped=%d truncated=%d "
                "tierFailures=%d tracesReaped=%d -- absence of an edge is NOT evidence",
                window.build_sha, window.instance_id, edge_health.dropped,
                edge_health.truncated_total, edge_health.tier_failures,
                edge_health.traces_reaped,
            )

        if window.agent_health.degraded:
            log.warning(
                "degraded window accepted build=%s instance=%s ringDropped=%d "
                "transformFailures=%d -- usable as LIFE evidence only, never as death evidence",
                window.build_sha, window.instance_id,
                window.agent_health.ring_dropped, window.agent_health.transform_failures,
            )

        # Bug #22b bookkeeping: what a non-production window did NOT contribute.
        # Zero on a production window by construction -- `evidence` is the same
        # flag that gated every merge above, so these can never disagree with
        # what actually happened.
        withheld_coverage = 0 if evidence else len(window.coverage)
        withheld_bits = 0 if evidence else sum(popcount(r.probes) for r in window.coverage)
        withheld_loaded = 0 if evidence else len(window.classes_loaded)
        withheld_edges = 0 if evidence else len(window.edges)

        # Bug #24 per-window attribution, summed from the records the decoder
        # already resolved. `unattributed` is NOT an error here: it is the
        # legal remainder of a 254-entry table plus an overflow bucket.
        errors_attributed = sum(r.attributed_errors for r in window.tier2)
        errors_unattributed = sum(r.unattributed_errors for r in window.tier2)
        ids_unresolved = sum(r.unresolved_id_errors for r in window.tier2)
        no_types = sum(
            1 for r in window.tier2 if r.errors and not r.error_types_available
        )
        if errors_unattributed or ids_unresolved:
            log.info(
                "tier-2 errors partially unattributed build=%s instance=%s: %d named, "
                "%d unattributed (%d under an id this window's errorClasses table did "
                "not name). This is legal -- 254-class table, id 255 = overflow, errors "
                "counted unconditionally -- and is NEVER reconciled by inventing a class",
                window.build_sha, window.instance_id, errors_attributed,
                errors_unattributed, ids_unresolved,
            )

        self.health.accept(
            degraded=window.agent_health.degraded,
            classes_merged=merged,
            classes_loaded=len(window.classes_loaded) if evidence else 0,
            probes_newly_set=fresh_bits,
            tier2_records=len(window.tier2),
            discarded_test_records=discarded,
            edge_records=edges_merged,
            edge_observations=edge_observations,
            edges_reported=window.edges_present,
            edges_enabled=edge_health.enabled,
            edges_sample_rate=edge_health.sample_rate,
            edges_verified=verified or not window.edges or not evidence,
            install_mask_records=install_merged,
            records_without_install_mask=install_absent,
            strip_mask_missing=window.agent_health.strip_mask_missing,
            production=window.production,
            environment=window.environment,
            coverage_records_withheld=withheld_coverage,
            probe_bits_withheld=withheld_bits,
            classes_loaded_withheld=withheld_loaded,
            edges_withheld=withheld_edges,
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
            edges_merged=edges_merged,
            edge_sampled_observations=edge_observations,
            edges_sample_rate=edge_health.sample_rate or None,
            edges_enabled=edge_health.enabled,
            edges_reported=window.edges_present,
            edge_endpoints_verified=verified,
            install_masks_merged=install_merged,
            records_without_install_mask=install_absent,
            install_mask_inconsistencies=install_inconsistent,
            install_masks_dropped=install_unsupported + install_dropped,
            strip_mask_missing=window.agent_health.strip_mask_missing,
            environment=window.environment,
            production=window.production,
            liveness_evidence=window.liveness_evidence and window.production,
            coverage_records_withheld=withheld_coverage,
            probe_bits_withheld=withheld_bits,
            classes_loaded_withheld=withheld_loaded,
            edges_withheld=withheld_edges,
            error_classes_named=error_classes_named,
            errors_attributed=errors_attributed,
            errors_unattributed=errors_unattributed,
            error_class_ids_unresolved=ids_unresolved,
            tier2_records_without_error_types=no_types,
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
