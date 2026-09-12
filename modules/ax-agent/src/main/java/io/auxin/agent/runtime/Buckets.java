package io.auxin.agent.runtime;

/**
 * Log-linear bucketing, 16 sub-buckets per octave, pure bit manipulation — no {@code Math.log}
 * (C30, and Datadog's reason for {@code BitwiseLinearlyInterpolatedMapping}: "the logarithm may
 * be costly to compute").
 *
 * <pre>
 *   n &lt; 16 : index = n                          (exact, 1ns resolution)
 *   else    : e = floor(log2(n)); sub = (n &gt;&gt;&gt; (e-4)) &amp; 15; index = (e-3)*16 + sub
 * </pre>
 *
 * Worst-case relative error is 1/32 (~3%). Max index for a 63-bit nanosecond value is 959, so
 * {@code long[1024]} covers the whole range with room to spare.
 *
 * <p>The bucket index is computed on the application thread (it is one shift and one mask, not
 * aggregation); the histogram increment happens on the drain thread (C29).
 */
public final class Buckets {

    public static final int COUNT = 1024;

    public static int of(long nanos) {
        if (nanos <= 0) return 0;
        if (nanos < 16) return (int) nanos;
        int e = 63 - Long.numberOfLeadingZeros(nanos);
        int sub = (int) ((nanos >>> (e - 4)) & 0xF);
        int idx = ((e - 3) << 4) + sub;
        return idx >= COUNT ? COUNT - 1 : idx;
    }

    /** Lower bound of the bucket in nanoseconds — for tests and for documenting the scheme. */
    public static long lowerBound(int index) {
        if (index < 16) return index;
        int e = (index >>> 4) + 3;
        int sub = index & 0xF;
        return ((long) (16 + sub)) << (e - 4);
    }

    private Buckets() { throw new AssertionError(); }
}
