package io.auxin.agent.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * The shadow snapshot (A14 defect 5 / PLAN-v2 "Never clear probe arrays in place").
 *
 * <p>JaCoCo's {@code reset()} zeroes the array in place and loses every probe set concurrently
 * with the reset. Here the live arrays accumulate for the life of the JVM and are never written
 * by anything but a probe; the drain thread keeps its own copy and diffs against it. Worst case
 * on a lost flush is a repeated window, never a lost bit.
 *
 * <p>Drain-thread owned. Not thread safe, deliberately.
 */
public final class CoverageSnapshot {

    private final Map<String, boolean[]> shadow = new HashMap<String, boolean[]>();

    /** @return true when at least one new probe has been set since the last call. */
    public boolean changedSince(String className, boolean[] live) {
        boolean[] prev = shadow.get(className);
        if (prev == null || prev.length != live.length) {
            shadow.put(className, live.clone());
            return anySet(live);
        }
        boolean changed = false;
        for (int i = 0; i < live.length; i++) {
            if (live[i] && !prev[i]) {
                prev[i] = true;
                changed = true;
            }
        }
        return changed;
    }

    public static boolean anySet(boolean[] a) {
        for (int i = 0; i < a.length; i++) if (a[i]) return true;
        return false;
    }

    public static boolean allSet(boolean[] a) {
        if (a.length == 0) return false;
        for (int i = 0; i < a.length; i++) if (!a[i]) return false;
        return true;
    }

    public static int countSet(boolean[] a) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (a[i]) n++;
        return n;
    }

    /** Packed bitset, LSB = idx 0, base64 (CONTRACTS section 2). */
    public static String toBase64(boolean[] probes) {
        byte[] packed = new byte[(probes.length + 7) / 8];
        for (int i = 0; i < probes.length; i++) {
            if (probes[i]) packed[i >>> 3] |= (byte) (1 << (i & 7));
        }
        return java.util.Base64.getEncoder().encodeToString(packed);
    }
}
