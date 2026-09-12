package io.auxin.agent.runtime;

import io.auxin.agent.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;

/**
 * Clock self-calibration (C24). The load-bearing assumption is not "nanoTime is cheap" — it is
 * "the target clocksource is tsc or kvm-clock". A4 measured 25-27ns on tsc/kvm-clock and
 * <b>367ns on Xen</b>; Brendan Gregg found {@code os::javaTimeMillis()} at 32.1% of total CPU on
 * Ubuntu/Xen. So we measure it in premain instead of assuming it.
 *
 * <pre>
 *   &lt;= ax.clock.sampled.threshold.ns  (default  60) : tier-2 times every call
 *   &gt;  ax.clock.sampled.threshold.ns                : tier-2 times 1 call in 64
 *   &gt;  ax.clock.disabled.threshold.ns (default 200) : tier-2 timing OFF, clockDegraded = true
 * </pre>
 *
 * <h3>BUG #25: the same machine classified itself differently on two boots</h3>
 * Field report: one boot printed {@code timing mode=disabled} off a 250ns nanoTime pair, the next
 * printed {@code sampled}. Two causes, both fixed here.
 *
 * <p><b>1. The old decision used the median of back-to-back pairs, which measures the timer's
 * GRANULARITY and not the call's COST.</b> VALIDATION A4 / the Linux gate (L1/L2) measured this
 * directly: on arm64 the generic timer ticks at ~41.67ns, so a pair can never read below one
 * tick and the median reports 41-42ns however cheap the call is — 2.7x the 15ns amortised truth,
 * and uncomfortably near the 60ns line on a <i>healthy</i> machine. The decision now uses the
 * <b>amortised</b> figure, which is what L1/L2 recommended: total elapsed time over the whole
 * loop divided by the number of calls. It includes loop and array-store overhead, so it is an
 * upper bound on the per-call cost — it cannot flatter a slow clock (a 367ns Xen call still
 * shows up as ~367ns), it just stops charging arm64 for its tick size.
 *
 * <p><b>2. One sample of 10k pairs at premain is measured while the JVM is at its noisiest.</b>
 * Five independent rounds are measured and the decision takes the <b>minimum</b> round, because
 * this is a floor measurement: interference from another thread, an interrupt or a C2 compilation
 * can only ever make a round look slower, never faster. The minimum is therefore the best
 * estimate of the machine's real cost and — the point — it is the statistic that does not move
 * between boots. The spread across rounds is reported, so a genuinely unstable host is visible
 * rather than inferred.
 *
 * <p><b>Why min-of-rounds and not hysteresis.</b> Hysteresis needs the previous decision, and
 * there is nowhere to keep one: every boot is a fresh JVM, writing state to disk from a premain
 * is not something an agent gets to do, and a pod's disk does not survive a reschedule anyway.
 * What hysteresis would have bought is instead bought twice over: a robust statistic that does
 * not straddle the threshold, plus {@link #borderline()} — if the measurement lands within
 * {@value #BORDERLINE_PERCENT}% of a threshold the agent says so and names the property that
 * pins it, so a host that really is on the boundary is a diagnosable configuration problem
 * instead of a coin flip nobody can see.
 */
public final class Clock {

    public static final int MODE_FULL = 0;
    public static final int MODE_SAMPLED = 1;
    public static final int MODE_DISABLED = 2;

    /** Rounds of {@link #ITERATIONS_PER_ROUND}. 5 x 10k pairs is ~1-4ms of the premain budget. */
    private static final int ROUNDS = 5;
    private static final int ITERATIONS_PER_ROUND = 10000;

    /** A measurement this close to a threshold is announced as boot-to-boot unstable. */
    private static final int BORDERLINE_PERCENT = 20;

    /** Reported on the wire as agentHealth.clockNs. */
    private final long nanosPerCall;
    /** The figure the mode was decided on: min over rounds of the amortised per-call cost. */
    private final long costNs;
    /** Min over rounds of the median pair delta: the timer's granularity, not its cost. */
    private final long granularityNs;
    /** Spread of the round medians. Non-trivial means the host itself is noisy. */
    private final long jitterNs;
    private final int mode;
    private final String clocksource;
    private final boolean borderline;

    private Clock(long nanosPerCall, long costNs, long granularityNs, long jitterNs, int mode,
                  String clocksource, boolean borderline) {
        this.nanosPerCall = nanosPerCall;
        this.costNs = costNs;
        this.granularityNs = granularityNs;
        this.jitterNs = jitterNs;
        this.mode = mode;
        this.clocksource = clocksource;
        this.borderline = borderline;
    }

    /**
     * @param sampledThresholdNs  {@code ax.clock.sampled.threshold.ns}
     * @param disabledThresholdNs {@code ax.clock.disabled.threshold.ns}
     */
    public static Clock calibrate(long sampledThresholdNs, long disabledThresholdNs) {
        final long[] samples = new long[ITERATIONS_PER_ROUND];
        // warm the intrinsic before measuring
        for (int i = 0; i < 2000; i++) {
            if (System.nanoTime() == 42) samples[0] = 1;
        }

        long bestMedian = Long.MAX_VALUE;
        long worstMedian = 0;
        long bestAmortised = Long.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            final long loopStart = System.nanoTime();
            for (int i = 0; i < ITERATIONS_PER_ROUND; i++) {
                long a = System.nanoTime();
                long b = System.nanoTime();
                samples[i] = b - a;
            }
            final long loopEnd = System.nanoTime();
            Arrays.sort(samples);
            final long median = samples[ITERATIONS_PER_ROUND / 2];
            final long amortised = (loopEnd - loopStart) / (ITERATIONS_PER_ROUND * 2L);
            if (median < bestMedian) bestMedian = median;
            if (median > worstMedian) worstMedian = median;
            if (amortised < bestAmortised) bestAmortised = amortised;
        }

        // THE DECISION. The amortised figure (L1/L2's recommendation), from the quietest round.
        final long cost = bestAmortised;
        // What we PUBLISH stays the conservative max of the two, as before: the wire number must
        // never be able to hide a slow clock, and on arm64 the granularity is the bigger of them.
        final long reported = Math.max(bestMedian, bestAmortised);

        final String src = readClocksource();
        final int mode = cost > disabledThresholdNs
                ? MODE_DISABLED
                : (cost > sampledThresholdNs ? MODE_SAMPLED : MODE_FULL);
        final boolean borderline = near(cost, sampledThresholdNs) || near(cost, disabledThresholdNs);

        final Clock c = new Clock(reported, cost, bestMedian, worstMedian - bestMedian, mode, src,
                borderline);
        Log.info("clock: cost=" + cost + "ns (min amortised of " + ROUNDS + "x"
                + ITERATIONS_PER_ROUND + " pairs, the figure the mode is decided on)"
                + ", granularity=" + bestMedian + "ns (min median pair, jitter "
                + (worstMedian - bestMedian) + "ns)"
                + (src == null ? "" : ", clocksource=" + src)
                + ", thresholds sampled>" + sampledThresholdNs + "ns disabled>"
                + disabledThresholdNs + "ns"
                + ", tier2 timing mode=" + c.modeName()
                // BUG #25: the mode's CONSEQUENCE, on the startup line, in every mode. The field
                // reporter had to read Tier2Aggregator to find out that `calls` is incremented
                // unconditionally -- "the difference between 'tier-2 is dead on this host' and
                // 'you still get counts'".
                + " (" + c.consequence() + ")");
        if (mode != MODE_FULL) {
            Log.warn("clock is slow on this host (cost=" + cost + "ns, granularity=" + bestMedian
                    + "ns" + (src == null ? "" : ", clocksource=" + src) + "). Tier-2 timing is "
                    + c.modeName() + ": " + c.consequence() + ". Check that the host clocksource "
                    + "is tsc or kvm-clock; the thresholds are ax.clock.sampled.threshold.ns and "
                    + "ax.clock.disabled.threshold.ns. Reported on the wire as "
                    + "agentHealth.tier2TimingMode=" + c.wireMode() + ".");
        }
        if (borderline) {
            Log.warn("clock cost=" + cost + "ns is within " + BORDERLINE_PERCENT + "% of a "
                    + "tier-2 timing threshold (sampled>" + sampledThresholdNs + "ns, disabled>"
                    + disabledThresholdNs + "ns), so THIS HOST MAY CLASSIFY DIFFERENTLY ON THE "
                    + "NEXT BOOT. Pin it with ax.clock.sampled.threshold.ns / "
                    + "ax.clock.disabled.threshold.ns. Whichever way it lands, calls and errors "
                    + "are recorded exactly; only latency buckets differ.");
        }
        return c;
    }

    /** Linux only; absent on macOS and on most containers without /sys mounted. */
    private static String readClocksource() {
        File f = new File("/sys/devices/system/clocksource/clocksource0/current_clocksource");
        if (!f.isFile()) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
    }

    private static boolean near(long value, long threshold) {
        if (threshold <= 0) return false;
        final long margin = (threshold * BORDERLINE_PERCENT) / 100L;
        return value >= threshold - margin && value <= threshold + margin;
    }

    public long nanosPerCall() { return nanosPerCall; }
    public long costNs() { return costNs; }
    public long granularityNs() { return granularityNs; }
    public long jitterNs() { return jitterNs; }
    public int mode() { return mode; }
    public boolean degraded() { return mode == MODE_DISABLED; }
    public boolean borderline() { return borderline; }
    public String clocksource() { return clocksource; }

    public String modeName() {
        switch (mode) {
            case MODE_FULL: return "full";
            case MODE_SAMPLED: return "sampled-1-in-64";
            default: return "disabled";
        }
    }

    /**
     * The spelling CONTRACTS section 2 pins for {@code agentHealth.tier2TimingMode}:
     * {@code full|sampled|disabled}. Separate from {@link #modeName()} so the human-readable
     * "sampled-1-in-64" can keep saying what the sampling rate is without a collector having to
     * parse it.
     */
    public String wireMode() {
        switch (mode) {
            case MODE_FULL: return "full";
            case MODE_SAMPLED: return "sampled";
            default: return "disabled";
        }
    }

    /**
     * What this mode costs the data, in one clause. <b>Calls and errors are never affected</b>:
     * {@code Tier2Aggregator.onEvent} increments {@code calls} and classifies the error for every
     * event, timed or not, and {@code Tier2Runtime} keeps emitting an event per call with
     * {@code start == 0} when timing is off.
     */
    public String consequence() {
        switch (mode) {
            case MODE_FULL:
                return "calls, errors and latency buckets all recorded";
            case MODE_SAMPLED:
                return "calls and errors still counted EXACTLY, for every call; only 1 call in 64 "
                        + "contributes to the latency buckets, so percentiles are coarser";
            default:
                return "calls and errors are STILL COUNTED EXACTLY -- only the latency buckets are "
                        + "lost, so tier-2 is not dead on this host, it just has no percentiles";
        }
    }
}
