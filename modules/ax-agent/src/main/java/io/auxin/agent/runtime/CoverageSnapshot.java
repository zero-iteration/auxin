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

    /**
     * Tier-1b's strip gate: is every probe that was <b>actually installed</b> set?
     *
     * <p>This replaced a plain "every bit in the array is set" test, which could never be
     * satisfied by any class carrying one C51-exempt method. The probe array is sized to every
     * probe-eligible method in the manifest, but {@code ProbeEmitter} installs a probe at only
     * some of those indices; a slot with no probe is never written by anything, so the old gate
     * was false for the life of the JVM and the class's probes stayed on the hot path — the exact
     * cost Tier-1b exists to remove.
     *
     * <p>Returns false when the installed set is EMPTY, deliberately. A class with no installed
     * probe has nothing to strip, so calling it strippable would send a retransform that removes
     * nothing and then has to be graded a no-op — inflating the bookkeeping with exactly the
     * kind of phantom success G5-BUG-3 removed. Such a class is not a candidate at all.
     *
     * <p>Also false on any disagreement between the two arrays: a length mismatch means we do not
     * know what we are looking at, and the answer to that is to leave the probes alone.
     *
     * @param live      the accumulating probe array
     * @param installed the installed-probe mask from {@code ProbeHolder.installedProbes}
     */
    public static boolean allInstalledSet(boolean[] live, boolean[] installed) {
        if (live == null || installed == null || live.length != installed.length) return false;
        boolean anyInstalled = false;
        for (int i = 0; i < live.length; i++) {
            if (!installed[i]) continue;
            anyInstalled = true;
            if (!live[i]) return false;
        }
        return anyInstalled;
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
