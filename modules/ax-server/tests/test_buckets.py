"""Server-side percentiles from log-linear buckets (CONTRACTS 2 / C31)."""

import pytest

from ax_server.collector.buckets import (
    BUCKET_SCHEME,
    UnknownBucketScheme,
    bucket_bounds,
    bucket_index,
    merge_buckets,
    percentile,
    percentiles,
)


@pytest.mark.parametrize("value", [0, 1, 15, 16, 31, 32, 33, 63, 64, 100, 1000, 10**6])
def test_bucket_index_and_bounds_are_inverse(value):
    idx = bucket_index(value)
    low, high = bucket_bounds(idx)
    assert low <= value < high


def test_buckets_are_contiguous_and_16_per_octave():
    for idx in range(300):
        low, high = bucket_bounds(idx)
        assert bucket_index(low) == idx
        assert bucket_index(high - 1) == idx
        if idx:
            assert bucket_bounds(idx - 1)[1] == low
    # 16 sub-buckets per octave above the linear region.
    widths = {bucket_bounds(i)[1] - bucket_bounds(i)[0] for i in range(32, 48)}
    assert widths == {2}


def test_percentile_on_the_contract_example():
    # The CONTRACTS 2 example: buckets [0,0,14,881,306,0].
    buckets = [0, 0, 14, 881, 306, 0]
    assert percentile(buckets, 50.0) == 3
    assert percentile(buckets, 99.0) == 4
    assert percentiles(buckets) == {"p50": 3, "p90": 4, "p99": 4}


def test_percentile_is_none_without_samples():
    assert percentile([0, 0, 0], 99.0) is None
    assert percentiles([])["p50"] is None


def test_percentile_monotonic():
    buckets = [0] * 60
    buckets[10] = 100
    buckets[40] = 10
    p50, p90, p99 = (percentile(buckets, p) for p in (50, 90, 99))
    assert p50 <= p90 <= p99
    assert p50 == 10


def test_merge_buckets_widens_ragged_arrays():
    assert merge_buckets([1, 2], [1, 1, 5]) == [2, 3, 5]


def test_unknown_scheme_is_refused_not_guessed():
    with pytest.raises(UnknownBucketScheme):
        percentiles([1, 2, 3], scheme="hdr-v2")
    assert BUCKET_SCHEME == "loglinear-16-v1"
