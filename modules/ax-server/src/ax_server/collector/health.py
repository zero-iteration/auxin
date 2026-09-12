"""Ingest health counters.

PLAN-v2 non-negotiables: "ship `ax_transform_failures_total` and
`ax_classes_skipped_total{reason}` from day one". The same logic applies to the
collector: a rejection that nobody counts is a silent data loss, and silent
data loss in THIS system manufactures dead-code verdicts.
"""

import threading
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

__all__ = ["IngestHealth", "RejectReason"]


class RejectReason:
    """Stable identifiers for every way an ingest can be refused."""

    MALFORMED_BODY = "malformed_body"
    BAD_GZIP = "bad_gzip"
    SCHEMA_VERSION = "schema_version_mismatch"
    MISSING_FIELD = "missing_field"
    BAD_FIELD = "bad_field"
    MISSING_AGENT_HEALTH = "missing_agent_health"
    BAD_PROBE_BITSET = "bad_probe_bitset"
    SCHEMA_HASH_MISMATCH = "schema_hash_mismatch"
    UNKNOWN_BUCKET_SCHEME = "unknown_bucket_scheme"
    NOT_PRODUCTION = "not_production_classified"
    TEST_RUNNER_WINDOW = "test_runner_window"
    TEST_RUNNER_RECORD = "test_runner_record"
    PAYLOAD_TOO_LARGE = "payload_too_large"
    #: SCOPE-v3: an `edges[]` entry named a `(class, idx)` the manifest does
    #: not declare. Storing it would create a dangling edge that no query can
    #: ever resolve, so the whole window is refused, loudly.
    UNKNOWN_EDGE_ENDPOINT = "unknown_edge_endpoint"
    EDGES_UNVERIFIED = "edges_unverified"
    #: Bug #18: a `coverage[]` record whose `probes` has a bit set at an index
    #: its own `probesInstalled` says was never instrumented. A probe that
    #: fired must have been installed, so the mask is stale or wrong. Counted,
    #: not fatal: the set bit is still trustworthy evidence of LIFE, and the
    #: mask is only ever used to WITHHOLD death evidence.
    PROBE_INSTALL_MASK_INCONSISTENT = "probe_install_mask_inconsistent"
    #: The store adapter has no `ProbeInstallStore`, so a reported mask was
    #: discarded. Nothing is misattributed -- the analysis falls back to "no
    #: mask reported" -- but the #18 gate is not running.
    PROBE_INSTALL_MASK_DISCARDED = "probe_install_mask_discarded"


@dataclass
class IngestHealth:
    """Process-local counters. Cheap, lock-guarded, snapshot-able."""

    windows_accepted: int = 0
    windows_rejected: int = 0
    windows_degraded: int = 0
    bytes_in: int = 0
    bytes_decompressed: int = 0
    classes_merged: int = 0
    classes_loaded_recorded: int = 0
    probes_newly_set: int = 0
    tier2_records: int = 0
    coverage_records_discarded_test: int = 0
    #: SCOPE-v3 edge tier. `edge_records` counts EDGES merged;
    #: `edge_observations` sums their sampled counts, which is NOT a call
    #: total -- see `edges_sample_rates` for the divisor.
    edge_records: int = 0
    edge_observations: int = 0
    edge_windows_reporting: int = 0
    edge_windows_enabled: int = 0
    edge_windows_unverified: int = 0
    edges_sample_rates: Counter[int] = field(default_factory=Counter)
    #: Bug #18. `install_mask_records` counts coverage records that CARRIED a
    #: `probesInstalled` mask; `coverage_records_without_install_mask` counts
    #: those that did not (a pre-#18 agent). The two are reported separately
    #: because an absent mask leaves the older behaviour in place while a
    #: reported one gates the death argument -- and an operator needs to know
    #: which of those is happening.
    install_mask_records: int = 0
    coverage_records_without_install_mask: int = 0
    install_mask_windows: int = 0
    strip_mask_missing: int = 0
    strip_mask_missing_windows: int = 0
    rejects_by_reason: Counter[str] = field(default_factory=Counter)
    schema_hash_mismatches_by_class: Counter[str] = field(default_factory=Counter)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def reject(self, reason: str, *, detail: str | None = None) -> None:
        with self._lock:
            self.windows_rejected += 1
            self.rejects_by_reason[reason] += 1
            if reason == RejectReason.SCHEMA_HASH_MISMATCH and detail:
                self.schema_hash_mismatches_by_class[detail] += 1

    def accept(
        self,
        *,
        degraded: bool,
        classes_merged: int,
        classes_loaded: int,
        probes_newly_set: int,
        tier2_records: int,
        discarded_test_records: int,
        edge_records: int = 0,
        edge_observations: int = 0,
        edges_reported: bool = False,
        edges_enabled: bool = False,
        edges_sample_rate: int = 0,
        edges_verified: bool = True,
        install_mask_records: int = 0,
        records_without_install_mask: int = 0,
        strip_mask_missing: int = 0,
    ) -> None:
        with self._lock:
            self.windows_accepted += 1
            if degraded:
                self.windows_degraded += 1
            self.classes_merged += classes_merged
            self.classes_loaded_recorded += classes_loaded
            self.probes_newly_set += probes_newly_set
            self.tier2_records += tier2_records
            self.coverage_records_discarded_test += discarded_test_records
            self.edge_records += edge_records
            self.edge_observations += edge_observations
            if edges_reported:
                self.edge_windows_reporting += 1
                self.edges_sample_rates[int(edges_sample_rate)] += 1
            if edges_enabled:
                self.edge_windows_enabled += 1
            if not edges_verified:
                self.edge_windows_unverified += 1
                self.rejects_by_reason[RejectReason.EDGES_UNVERIFIED] += 1
            self.install_mask_records += install_mask_records
            self.coverage_records_without_install_mask += records_without_install_mask
            if install_mask_records:
                self.install_mask_windows += 1
            self.strip_mask_missing += strip_mask_missing
            if strip_mask_missing:
                self.strip_mask_missing_windows += 1

    def observe_bytes(self, raw: int, decompressed: int) -> None:
        with self._lock:
            self.bytes_in += raw
            self.bytes_decompressed += decompressed

    def count_partial(self, reason: str, detail: str | None = None) -> None:
        """A per-record problem inside an otherwise accepted window."""
        with self._lock:
            self.rejects_by_reason[reason] += 1
            if reason == RejectReason.SCHEMA_HASH_MISMATCH and detail:
                self.schema_hash_mismatches_by_class[detail] += 1

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "windowsAccepted": self.windows_accepted,
                "windowsRejected": self.windows_rejected,
                "windowsDegraded": self.windows_degraded,
                "bytesIn": self.bytes_in,
                "bytesDecompressed": self.bytes_decompressed,
                "classesMerged": self.classes_merged,
                "classesLoadedRecorded": self.classes_loaded_recorded,
                "probesNewlySet": self.probes_newly_set,
                "tier2Records": self.tier2_records,
                "coverageRecordsDiscardedTest": self.coverage_records_discarded_test,
                "edgeRecordsMerged": self.edge_records,
                # Named for what it is: a sum of SAMPLED observations, not calls.
                "edgeSampledObservations": self.edge_observations,
                "edgeWindowsReporting": self.edge_windows_reporting,
                "edgeWindowsTierEnabled": self.edge_windows_enabled,
                "edgeWindowsUnverified": self.edge_windows_unverified,
                "edgesSampleRates": {str(k or "undeclared"): v
                                     for k, v in sorted(self.edges_sample_rates.items())},
                "probeInstallMaskRecords": self.install_mask_records,
                "coverageRecordsWithoutInstallMask": self.coverage_records_without_install_mask,
                "windowsWithInstallMask": self.install_mask_windows,
                "stripMaskMissingTotal": self.strip_mask_missing,
                "stripMaskMissingWindows": self.strip_mask_missing_windows,
                "rejectsByReason": dict(self.rejects_by_reason),
                "schemaHashMismatchesByClass": dict(self.schema_hash_mismatches_by_class),
            }
