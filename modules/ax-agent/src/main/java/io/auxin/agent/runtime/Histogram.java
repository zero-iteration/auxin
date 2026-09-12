package io.auxin.agent.runtime;

/**
 * Plain, non-thread-safe, single-writer log-linear histogram owned by the drain thread (C30).
 *
 * <p>No HdrHistogram, no DDSketch, no {@code Recorder}, no {@code WriterReaderPhaser}: A8
 * measured 4 contended atomic RMWs per record for {@code Recorder}, plausibly 150-600ns at 8-16
 * application threads. Since ALL aggregation happens on one thread here, a plain array
 * increment is correct and costs ~2ns.
 */
public final class Histogram {

    private final long[] counts = new long[Buckets.COUNT];
    private long total;

    public void record(int bucket) {
        counts[bucket]++;
        total++;
    }

    public long total() { return total; }

    /** @return counts[0..lastNonZero], indexed by bucket index. Empty array when nothing recorded. */
    public long[] trimmed() {
        int last = -1;
        for (int i = counts.length - 1; i >= 0; i--) {
            if (counts[i] != 0) { last = i; break; }
        }
        if (last < 0) return new long[0];
        long[] out = new long[last + 1];
        System.arraycopy(counts, 0, out, 0, last + 1);
        return out;
    }

    /** Drain-thread only, called immediately after a flush has serialised the window. */
    public void reset() {
        java.util.Arrays.fill(counts, 0L);
        total = 0;
    }
}
