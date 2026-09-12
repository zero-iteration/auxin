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

from ax_server.collector.classification import NON_PRODUCTION_MEANING

__all__ = ["ERROR_ATTRIBUTION_NOTE", "IngestHealth", "RejectReason"]

#: BUG #24. Quoted next to every exception-class breakdown, because the one
#: wrong reading of these numbers -- "attributed + unattributed should equal
#: errors, so the difference is a bug" -- is exactly the reconciliation
#: CONTRACTS 2 v4 forbids.
ERROR_ATTRIBUTION_NOTE = (
    "sum(errorsByClass) may legitimately be LESS than `errors`: the agent's id table holds "
    "254 distinct classes, id 255 is the overflow bucket, and `errors` is incremented "
    "unconditionally. The shortfall is reported as UNATTRIBUTED and is never reconciled by "
    "inventing a class. `tier2RecordsWithoutErrorTypes` counts records with errors and no "
    "breakdown at all -- those report 'types unavailable', never 'zero types'."
)


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
    #: BUG #24: an `errorClasses` or `errorsByClass` key that is not a numeric
    #: string, or is outside the 1-255 id space. CONTRACTS 2 v4: "Parse to
    #: int; reject non-numeric." Refused rather than skipped -- skipping the
    #: key would silently drop the errors filed under it.
    BAD_ERROR_CLASS_ID = "bad_error_class_id"


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
    # -- BUG #22b: the non-production bucket --------------------------------
    #: Windows ACCEPTED and stored with `livenessEvidence=false`. These used to
    #: be 403s and therefore invisible; now they are counted here so a
    #: first-time user can see data arriving before it is evidence.
    windows_non_production: int = 0
    #: What those windows called themselves, so the fix is one CLI flag away
    #: (`--allow-environments staging` when the label really is production).
    non_production_environments: Counter[str] = field(default_factory=Counter)
    #: What a non-production window contributed NOTHING to, itemised. Each of
    #: these is a merge that was deliberately withheld, and each is the reason
    #: storing the window is safe.
    coverage_records_withheld_non_production: int = 0
    probe_bits_withheld_non_production: int = 0
    classes_loaded_withheld_non_production: int = 0
    edges_withheld_non_production: int = 0
    # -- BUG #24: exception-class attribution -------------------------------
    #: Errors we could name a class for, and errors we could not. NEVER summed
    #: into one number: the gap is legal (254-class table, id 255 = overflow,
    #: `errors` unconditional) and reporting it is the contract.
    errors_attributed: int = 0
    errors_unattributed: int = 0
    #: Errors filed under an id the window's own `errorClasses` table did not
    #: name. Counted, and reported as unattributed -- never given a name.
    error_class_ids_unresolved: int = 0
    #: tier-2 records with `errors > 0` and no usable breakdown at all. These
    #: report "types unavailable", never "zero types".
    tier2_records_without_error_types: int = 0
    tier2_records_with_error_types: int = 0
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
        production: bool = True,
        environment: str = "production",
        coverage_records_withheld: int = 0,
        probe_bits_withheld: int = 0,
        classes_loaded_withheld: int = 0,
        edges_withheld: int = 0,
    ) -> None:
        with self._lock:
            self.windows_accepted += 1
            if degraded:
                self.windows_degraded += 1
            if not production:
                # Bug #22b. Accepted, stored, and evidence for nothing -- so it
                # is counted in its own bucket and NOT in `windows_rejected`,
                # because it was not rejected, and NOT silently inside
                # `windows_accepted` alone, because it is not evidence.
                self.windows_non_production += 1
                self.non_production_environments[environment] += 1
                self.coverage_records_withheld_non_production += coverage_records_withheld
                self.probe_bits_withheld_non_production += probe_bits_withheld
                self.classes_loaded_withheld_non_production += classes_loaded_withheld
                self.edges_withheld_non_production += edges_withheld
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

    def note_tier2_errors(
        self,
        *,
        errors: int,
        attributed: int,
        unattributed: int,
        unresolved_ids: int,
        types_available: bool,
    ) -> None:
        """BUG #24 attribution accounting for one tier-2 record.

        `attributed + unattributed == errors` by construction and the two are
        kept as SEPARATE counters on purpose: a single "errors" total would
        let a shortfall in the breakdown disappear, and the shortfall is
        exactly what CONTRACTS 2 v4 says a reader must surface instead of
        reconciling.
        """
        with self._lock:
            self.errors_attributed += attributed
            self.errors_unattributed += unattributed
            self.error_class_ids_unresolved += unresolved_ids
            if types_available:
                self.tier2_records_with_error_types += 1
            elif errors:
                self.tier2_records_without_error_types += 1

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
                # -- BUG #22b -------------------------------------------
                "windowsNonProduction": self.windows_non_production,
                # Named for exactly what it counts: windows whose environment
                # label cleared the allowlist. NOT "usable as evidence" -- the
                # degraded and test-taint gates still apply downstream, and a
                # counter that overstates its own scope is how a safety gate
                # gets quietly assumed.
                "windowsProductionClassified": (
                    self.windows_accepted - self.windows_non_production
                ),
                "nonProductionEnvironments": dict(self.non_production_environments),
                "nonProductionWithheld": {
                    "coverageRecords": self.coverage_records_withheld_non_production,
                    "probeBits": self.probe_bits_withheld_non_production,
                    "classesLoaded": self.classes_loaded_withheld_non_production,
                    "edges": self.edges_withheld_non_production,
                },
                "nonProductionNote": NON_PRODUCTION_MEANING,
                # -- BUG #24 -------------------------------------------
                "exceptionAttribution": {
                    "errorsAttributed": self.errors_attributed,
                    "errorsUnattributed": self.errors_unattributed,
                    "errorClassIdsUnresolved": self.error_class_ids_unresolved,
                    "tier2RecordsWithErrorTypes": self.tier2_records_with_error_types,
                    "tier2RecordsWithoutErrorTypes":
                        self.tier2_records_without_error_types,
                    "note": ERROR_ATTRIBUTION_NOTE,
                },
                "rejectsByReason": dict(self.rejects_by_reason),
                "schemaHashMismatchesByClass": dict(self.schema_hash_mismatches_by_class),
            }
