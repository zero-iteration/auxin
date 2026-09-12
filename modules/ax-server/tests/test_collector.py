"""Ingest: CONTRACTS 2 validation, gzip, schema mismatch, health counters."""

import gzip
import json
from datetime import timedelta

import pytest

from ax_server.collector.errors import IngestRejected
from ax_server.collector.health import RejectReason
from ax_server.store.bitset import set_bits

from conftest import BUILD_SHA, DAY0, payload, probes, realistic_payload

CLS = "com.acme.shipping.RateSelector"


def test_accepts_the_contract_example_shape(collector):
    result = collector.ingest(realistic_payload(0))
    assert result.accepted
    assert result.classes_merged == 6
    assert result.probes_newly_set == 2
    assert sorted(set_bits(collector.store.coverage(BUILD_SHA, CLS))) == [0, 1]


def test_gzip_body_is_decoded(collector):
    body = gzip.compress(json.dumps(realistic_payload(0)).encode())
    result = collector.ingest_bytes(body, content_encoding="gzip")
    assert result.accepted
    assert collector.health.snapshot()["bytesDecompressed"] > len(body) // 2


def test_plain_json_body_also_works(collector):
    result = collector.ingest_bytes(json.dumps(realistic_payload(0)).encode())
    assert result.accepted


def test_corrupt_gzip_is_rejected(collector):
    with pytest.raises(IngestRejected) as exc:
        collector.ingest_bytes(b"\x1f\x8b" + b"garbage", content_encoding="gzip")
    assert exc.value.reason == RejectReason.BAD_GZIP


def test_schema_version_mismatch_is_rejected_loudly(collector, caplog):
    """An UNKNOWN wire version is refused. Bug #17: this test used to use 2 -- the very version
    the real agent sends -- so it pinned a total pipeline break as correct behaviour. 99 is used
    now because the point of the refusal is that an unknown version may carry a different
    probe-index assignment (A14 defect 2), not that the number is large."""
    body = realistic_payload(0)
    body["schemaVersion"] = 99
    with caplog.at_level("ERROR"):
        with pytest.raises(IngestRejected) as exc:
            collector.ingest(body)
    assert exc.value.reason == RejectReason.SCHEMA_VERSION
    assert "INGEST REJECTED" in caplog.text
    assert collector.health.snapshot()["rejectsByReason"][RejectReason.SCHEMA_VERSION] == 1


def test_missing_agent_health_is_rejected(collector):
    body = realistic_payload(0)
    del body["agentHealth"]
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.MISSING_AGENT_HEALTH


def test_agent_sending_a_percentile_is_rejected(collector):
    body = realistic_payload(0)
    body["tier2"][0]["p99"] = 1234
    with pytest.raises(IngestRejected):
        collector.ingest(body)


def test_unknown_bucket_scheme_is_rejected(collector):
    body = realistic_payload(0)
    body["tier2"][0]["bucketScheme"] = "hdr-v2"
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.UNKNOWN_BUCKET_SCHEME


def test_percentiles_are_computed_server_side(collector):
    result = collector.ingest(realistic_payload(0))
    assert result.tier2_percentiles[f"{CLS}#0"] == {"p50": 3, "p90": 4, "p99": 4}


def test_schema_hash_mismatch_drops_the_class_loudly_and_keeps_the_rest(collector, caplog):
    collector.ingest(realistic_payload(0))
    bad = realistic_payload(1)
    bad["coverage"][0]["schemaHash"] = "DIFFERENT"
    bad["coverage"][0]["probes"] = probes(0, 1, 2, 3)
    with caplog.at_level("ERROR"):
        result = collector.ingest(bad)
    assert result.accepted
    assert result.schema_mismatches == (CLS,)
    assert "SCHEMA MISMATCH" in caplog.text
    # Not merged, and NOT zeroed.
    assert sorted(set_bits(collector.store.coverage(BUILD_SHA, CLS))) == [0, 1]
    snapshot = collector.health.snapshot()
    assert snapshot["schemaHashMismatchesByClass"][CLS] == 1
    assert collector.store.rejects(BUILD_SHA)[0]["reason"] == RejectReason.SCHEMA_HASH_MISMATCH


def test_degraded_window_is_accepted_and_flagged(collector):
    body = realistic_payload(0)
    body["agentHealth"]["degraded"] = True
    result = collector.ingest(body)
    assert result.accepted and result.degraded
    window = collector.store.observed_windows(BUILD_SHA)[0]
    assert window.degraded is True
    assert window.usable_as_death_evidence is False


def test_ingest_health_counters_are_exposed(collector):
    collector.ingest(realistic_payload(0))
    snap = collector.health.snapshot()
    assert snap["windowsAccepted"] == 1
    assert snap["classesMerged"] == 6
    assert snap["classesLoadedRecorded"] == 6
    assert snap["tier2Records"] == 1
    assert snap["probesNewlySet"] == 2


def test_merge_across_pods_is_order_independent(store):
    from ax_server.collector.service import CollectorService

    a = CollectorService(store)
    start = DAY0
    p1 = payload(start=start, end=start + timedelta(hours=1), instance_id="pod-a",
                 classes_loaded=[CLS],
                 coverage=[{"class": CLS, "schemaHash": "h", "probes": probes(0)}])
    p2 = payload(start=start, end=start + timedelta(hours=1), instance_id="pod-b",
                 classes_loaded=[CLS],
                 coverage=[{"class": CLS, "schemaHash": "h", "probes": probes(2)}])
    a.ingest(p1)
    a.ingest(p2)
    assert sorted(set_bits(store.coverage(BUILD_SHA, CLS))) == [0, 2]


def test_the_version_the_real_agent_actually_sends_is_accepted(collector):
    """Bug #17 regression. The agent's WIRE_SCHEMA_VERSION has been 2 since the C50 liveness
    fields landed; the collector accepted only 1, so every real window was rejected. Neither
    side's suite caught it -- the agent flushes to a throwaway listener that accepts anything,
    and these fixtures were hardcoded to 1. Pin BOTH readable versions here so the two
    components cannot drift apart again without a red test."""
    for version in (1, 2):
        body = realistic_payload(0)
        body["schemaVersion"] = version
        ack = collector.ingest(body)
        assert ack is not None, f"schemaVersion {version} must be readable"
