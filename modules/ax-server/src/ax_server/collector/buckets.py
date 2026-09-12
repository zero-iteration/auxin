"""The `loglinear-16-v1` bucket scheme and server-side percentiles.

CONTRACTS 2: "**Never send a percentile.** Buckets only; percentiles are
computed in `gt-collector` (C31)." The agent ships raw counts because the
p50/p90/p99 of a union of pods is NOT any function of the per-pod percentiles.

Layout -- 16 sub-buckets per octave (PLAN-v2 tier 2, `long[1024]`):

    group = idx // 16, sub = idx % 16
    group 0  (idx 0..15)   -> value == idx, width 1
    group g>=1             -> low = (16 + sub) << (g - 1), width = 1 << (g - 1)

so buckets are contiguous and each octave above the linear region is split
into 16 equal steps. Recording is the inverse.
"""

import math
from collections.abc import Sequence

__all__ = [
    "BUCKET_SCHEME",
    "SUB_BUCKET_COUNT",
    "UnknownBucketScheme",
    "bucket_bounds",
    "bucket_index",
    "merge_buckets",
    "percentile",
    "percentiles",
    "total_count",
]

BUCKET_SCHEME = "loglinear-16-v1"
SUB_BUCKET_BITS = 4
SUB_BUCKET_COUNT = 1 << SUB_BUCKET_BITS  # 16
_LINEAR_LIMIT = SUB_BUCKET_COUNT * 2  # values below this are 1:1 with the index

#: Percentiles the collector publishes for every tier-2 method.
DEFAULT_PERCENTILES: tuple[float, ...] = (50.0, 90.0, 99.0)


class UnknownBucketScheme(ValueError):
    """The agent sent buckets under a scheme this collector cannot read.

    Guessing here would silently produce wrong latencies, so we refuse.
    """

    def __init__(self, scheme: str) -> None:
        self.scheme = scheme
        super().__init__(
            f"unsupported bucketScheme {scheme!r}; this collector only understands "
            f"{BUCKET_SCHEME!r}"
        )


def bucket_index(value: int) -> int:
    """Index of the bucket that records `value`. Inverse of `bucket_bounds`."""
    if value < 0:
        raise ValueError(f"value must be non-negative, got {value}")
    if value < _LINEAR_LIMIT:
        return value
    octave = value.bit_length() - 1
    shift = octave - SUB_BUCKET_BITS
    sub = (value >> shift) & (SUB_BUCKET_COUNT - 1)
    return (shift + 1) * SUB_BUCKET_COUNT + sub


def bucket_bounds(idx: int) -> tuple[int, int]:
    """[low, high) value range recorded by bucket `idx`."""
    if idx < 0:
        raise ValueError(f"bucket index must be non-negative, got {idx}")
    group, sub = divmod(idx, SUB_BUCKET_COUNT)
    if group == 0:
        return sub, sub + 1
    shift = group - 1
    low = (SUB_BUCKET_COUNT + sub) << shift
    return low, low + (1 << shift)


def merge_buckets(a: Sequence[int], b: Sequence[int]) -> list[int]:
    """Element-wise sum, widening to the longer array.

    Trailing zeros may be omitted on the wire, so arrays are commonly ragged.
    """
    out = list(a) + [0] * max(0, len(b) - len(a))
    for i, count in enumerate(b):
        out[i] += int(count)
    return out


def total_count(buckets: Sequence[int]) -> int:
    return sum(int(c) for c in buckets)


def percentile(buckets: Sequence[int], p: float) -> int | None:
    """Value at percentile `p`, or None if there are no samples.

    Returns the HIGHEST value in the bucket the rank falls into -- the same
    convention as HdrHistogram's `getValueAtPercentile`. The true value lies in
    `bucket_bounds(idx)`; a histogram cannot be more precise than its bucket and
    pretending otherwise is how latency dashboards lie.
    """
    if not 0.0 <= p <= 100.0:
        raise ValueError(f"percentile must be in [0, 100], got {p}")
    n = total_count(buckets)
    if n == 0:
        return None
    target = max(1, math.ceil(p / 100.0 * n))
    seen = 0
    for idx, count in enumerate(buckets):
        seen += int(count)
        if seen >= target:
            low, high = bucket_bounds(idx)
            return high - 1
    low, high = bucket_bounds(len(buckets) - 1)
    return high - 1


def percentiles(
    buckets: Sequence[int],
    ps: Sequence[float] = DEFAULT_PERCENTILES,
    *,
    scheme: str = BUCKET_SCHEME,
) -> dict[str, int | None]:
    """Compute several percentiles at once, keyed `p50` / `p90` / `p99`."""
    if scheme != BUCKET_SCHEME:
        raise UnknownBucketScheme(scheme)
    out: dict[str, int | None] = {}
    for p in ps:
        label = f"p{p:g}".replace(".", "_")
        out[label] = percentile(buckets, p)
    return out
