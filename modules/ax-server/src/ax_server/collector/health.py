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
                "rejectsByReason": dict(self.rejects_by_reason),
                "schemaHashMismatchesByClass": dict(self.schema_hash_mismatches_by_class),
            }
