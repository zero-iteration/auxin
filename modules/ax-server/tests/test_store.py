"""Store port behaviour: OR-merge, schema mismatch, loaded-vs-invoked, windows."""

from datetime import UTC, datetime, timedelta

import pytest

from ax_server.store.bitset import from_indices, set_bits
from ax_server.store.errors import SchemaMismatch
from ax_server.store.models import AgentHealth, IngestWindow, Tier2Record

from conftest import BUILD_SHA, DAY0, ms

CLS = "com.acme.shipping.RateSelector"


def _window(store, *, degraded=False, classes=(), day=0, tier2=()):
    start = DAY0 + timedelta(days=day)
    store.record_window(
        IngestWindow(
            schema_version=1,
            build_sha=BUILD_SHA,
            artifact="checkout-service",
            instance_id="pod-1",
            window_start_ms=ms(start),
            window_end_ms=ms(start + timedelta(days=1)),
            agent_health=AgentHealth(degraded=degraded, ring_dropped=7 if degraded else 0),
            classes_loaded=tuple(classes),
            production=True,
            environment="production",
            tier2=tuple(tier2),
        )
    )


def test_merge_is_or_not_overwrite(store):
    store.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([0, 1]))
    store.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([3]))
    assert sorted(set_bits(store.coverage(BUILD_SHA, CLS))) == [0, 1, 3]


def test_merge_is_idempotent_through_the_store(store):
    bits = from_indices([0, 5, 9])
    for _ in range(3):
        store.merge_coverage(BUILD_SHA, CLS, "h1", bits)
    assert sorted(set_bits(store.coverage(BUILD_SHA, CLS))) == [0, 5, 9]


def test_merge_is_commutative_through_the_store(store):
    other = store.__class__(":memory:")
    store.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([0]))
    store.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([12]))
    other.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([12]))
    other.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([0]))
    assert store.coverage(BUILD_SHA, CLS) == other.coverage(BUILD_SHA, CLS)
    other.close()


def test_schema_mismatch_raises_and_does_not_merge(store):
    store.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([0]))
    with pytest.raises(SchemaMismatch) as exc:
        store.merge_coverage(BUILD_SHA, CLS, "DIFFERENT", from_indices([1, 2, 3]))
    assert exc.value.expected == "h1"
    assert exc.value.actual == "DIFFERENT"
    # Not silently merged...
    assert sorted(set_bits(store.coverage(BUILD_SHA, CLS))) == [0]
    # ...and NOT silently zeroed, which is the JaCoCo failure mode.
    assert store.coverage(BUILD_SHA, CLS) != b""


def test_first_and_last_seen_are_tracked_per_probe(store):
    t0 = datetime(2026, 9, 1, tzinfo=UTC)
    t1 = datetime(2026, 9, 5, tzinfo=UTC)
    with store.attribute_to(t0):
        store.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([0]))
    with store.attribute_to(t1):
        store.merge_coverage(BUILD_SHA, CLS, "h1", from_indices([0, 1]))
    assert store.first_seen(BUILD_SHA, CLS, 0) == t0
    assert store.last_seen(BUILD_SHA, CLS, 0) == t1
    assert store.first_seen(BUILD_SHA, CLS, 1) == t1
    assert store.first_seen(BUILD_SHA, CLS, 2) is None


def test_loaded_ever_is_separate_from_probe_bits(store):
    # C10: "never loaded" must be distinguishable from "loaded but never invoked".
    _window(store, classes=[CLS])
    assert store.class_loaded_ever(BUILD_SHA, CLS) is True
    assert store.coverage(BUILD_SHA, CLS) is None
    assert store.class_loaded_ever(BUILD_SHA, "com.acme.Nope") is False


def test_degraded_windows_are_queryable_as_such(store):
    _window(store, degraded=False, day=0)
    _window(store, degraded=True, day=1)
    windows = store.observed_windows(BUILD_SHA)
    assert [w.degraded for w in windows] == [False, True]
    degraded = [w for w in windows if w.degraded]
    assert len(degraded) == 1
    assert degraded[0].agent_health.ring_dropped == 7
    assert degraded[0].usable_as_death_evidence is False
    assert windows[0].usable_as_death_evidence is True


def test_tier2_buckets_sum_element_wise_across_ragged_windows(store):
    _window(store, day=0, tier2=[Tier2Record(CLS, 0, 10, 0, {}, (1, 2, 3), "loglinear-16-v1")])
    _window(store, day=1, tier2=[Tier2Record(CLS, 0, 10, 0, {}, (1, 1), "loglinear-16-v1")])
    since = DAY0 - timedelta(days=1)
    assert store.tier2_buckets(BUILD_SHA, CLS, 0, since) == [2, 3, 3]
