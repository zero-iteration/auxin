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
 *   &lt;= 60ns  : tier-2 times every call
 *   &gt; 60ns   : tier-2 times 1 call in 64 (calls are still counted exactly)
 *   &gt; 200ns  : tier-2 timing OFF, clockDegraded = true
 * </pre>
 */
public final class Clock {

    public static final int MODE_FULL = 0;
    public static final int MODE_SAMPLED = 1;
    public static final int MODE_DISABLED = 2;

    private static final int CALIBRATION_ITERATIONS = 10000;

    /** Reported on the wire as agentHealth.clockNs. */
    private final long nanosPerCall;
    private final int mode;
    private final String clocksource;

    private Clock(long nanosPerCall, int mode, String clocksource) {
        this.nanosPerCall = nanosPerCall;
        this.mode = mode;
        this.clocksource = clocksource;
    }

    public static Clock calibrate() {
        long[] samples = new long[CALIBRATION_ITERATIONS];
        // warm the intrinsic before measuring
        for (int i = 0; i < 1000; i++) {
            if (System.nanoTime() == 42) samples[0] = 1;
        }
        long loopStart = System.nanoTime();
        for (int i = 0; i < CALIBRATION_ITERATIONS; i++) {
            long a = System.nanoTime();
            long b = System.nanoTime();
            samples[i] = b - a;
        }
        long loopEnd = System.nanoTime();
        Arrays.sort(samples);

        // The MODE decision uses the median pair delta, as specified.
        long median = samples[CALIBRATION_ITERATIONS / 2];
        // But the median reads 0 whenever the clock's TICK is coarser than the call: on Apple
        // Silicon the timebase is ~41.67ns, so two back-to-back calls often land on the same
        // tick. The amortised cost over the whole loop is the honest number to report, and it
        // can never hide a slow clock (a 367ns Xen call still shows up in both).
        long amortised = (loopEnd - loopStart) / (CALIBRATION_ITERATIONS * 2L);
        long reported = Math.max(median, amortised);

        String src = readClocksource();
        int mode = median > 200 ? MODE_DISABLED : (median > 60 ? MODE_SAMPLED : MODE_FULL);
        Clock c = new Clock(reported, mode, src);
        Log.info("clock: median nanoTime pair = " + median + "ns, amortised = " + amortised + "ns"
                + (src == null ? "" : ", clocksource=" + src)
                + ", tier2 timing mode=" + c.modeName());
        if (mode != MODE_FULL) {
            Log.warn("clock is slow (" + median + "ns" + (src == null ? "" : ", clocksource=" + src)
                    + "). Tier-2 timing degraded to " + c.modeName()
                    + ". Check that the host clocksource is tsc or kvm-clock.");
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

    public long nanosPerCall() { return nanosPerCall; }
    public int mode() { return mode; }
    public boolean degraded() { return mode == MODE_DISABLED; }
    public String clocksource() { return clocksource; }

    public String modeName() {
        switch (mode) {
            case MODE_FULL: return "full";
            case MODE_SAMPLED: return "sampled-1-in-64";
            default: return "disabled";
        }
    }
}
