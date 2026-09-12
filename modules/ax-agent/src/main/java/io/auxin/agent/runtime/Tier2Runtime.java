package io.auxin.agent.runtime;

import io.auxin.agent.health.Health;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The tier-2 application-thread path. Instrumented methods call exactly these three methods.
 *
 * <p>Rules this code exists to honour:
 * <ul>
 *   <li><b>Zero allocation.</b> One packed {@code long}, one ring write. No object, no boxing,
 *       no varargs, no lambda.</li>
 *   <li><b>No aggregation here.</b> Counters and histograms belong to the drain thread (C29,
 *       dd-trace's model). This is the entire reason A8's contended-atomics problem does not
 *       exist in this design.</li>
 *   <li><b>Drop, never block.</b> One attempt at the ring; on failure increment a LongAdder and
 *       return (C27).</li>
 * </ul>
 */
public final class Tier2Runtime {

    /** Kill switch (tier level). Plain volatile read on the hot path. */
    private static volatile boolean enabled;
    /** False when the clock is too slow to time anything (Clock.MODE_DISABLED). */
    private static volatile boolean timingEnabled;
    /** True when timing is sampled 1-in-64 (Clock.MODE_SAMPLED). */
    private static volatile boolean sampledTiming;

    private static volatile Ring ring;

    public static void install(Ring r, Clock clock, boolean tier2Enabled) {
        ring = r;
        sampledTiming = clock.mode() == Clock.MODE_SAMPLED;
        timingEnabled = clock.mode() != Clock.MODE_DISABLED;
        enabled = tier2Enabled && r != null;
    }

    public static void disable() { enabled = false; }

    public static boolean enabled() { return enabled; }

    public static Ring ring() { return ring; }

    /** @return the start timestamp, or 0 when this call is not being timed. */
    public static long enter() {
        if (!enabled || !timingEnabled) return 0L;
        if (sampledTiming && (ThreadLocalRandom.current().nextInt() & 63) != 0) return 0L;
        return System.nanoTime();
    }

    /** Normal return. {@code start == 0} means "counted but not timed". */
    public static void exit(long start, int methodId) {
        if (!enabled) return;
        long event = start == 0L
                ? Events.pack(methodId, ErrorIds.NONE, 0, false)
                : Events.pack(methodId, ErrorIds.NONE, Buckets.of(System.nanoTime() - start), true);
        if (!ring.offer(event)) Health.RING_DROPPED.increment();
    }

    /** Exceptional exit. The throwable is rethrown by the instrumented method, never here. */
    public static void exitError(long start, int methodId, Throwable t) {
        if (!enabled) return;
        int errorId;
        try {
            errorId = t == null ? ErrorIds.OVERFLOW : ErrorIds.idFor(t.getClass());
        } catch (Throwable ignored) {
            errorId = ErrorIds.OVERFLOW;
        }
        long event = start == 0L
                ? Events.pack(methodId, errorId, 0, false)
                : Events.pack(methodId, errorId, Buckets.of(System.nanoTime() - start), true);
        if (!ring.offer(event)) Health.RING_DROPPED.increment();
    }

    private Tier2Runtime() { throw new AssertionError(); }
}
