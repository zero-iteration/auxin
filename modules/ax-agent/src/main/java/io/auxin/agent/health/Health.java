package io.auxin.agent.health;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Agent self-observability. Mandatory, not optional: the JVM silently ignores exceptions thrown
 * from a {@code ClassFileTransformer}, so without these counters a broken transform ships 40%
 * coverage and the analysis calls the other 60% dead code (VALIDATION C37).
 *
 * <p>Single JVM-wide instance; all counters are static. The only counter touched from an
 * application thread is {@link #RING_DROPPED} (a {@code LongAdder}, on the drop path only).
 */
public final class Health {

    // ---- skip reasons (the {reason} label of ax_classes_skipped_total) ----
    public static final String SKIP_NO_MANIFEST_ENTRY = "noManifestEntry";
    public static final String SKIP_SCHEMA_HASH_MISMATCH = "schemaHashMismatch";
    public static final String SKIP_BUDGET_EXCEEDED = "budgetExceeded";
    public static final String SKIP_PACKAGE_KILL_SWITCH = "packageKillSwitch";
    public static final String SKIP_NO_ELIGIBLE_METHODS = "noEligibleMethods";
    public static final String SKIP_LEGACY_INTERFACE = "legacyInterface";
    public static final String SKIP_NOT_DYNAMICALLY_OBSERVABLE = "notDynamicallyObservable";
    public static final String SKIP_METHOD_NOT_IN_MANIFEST = "methodNotInManifest";
    public static final String SKIP_TRANSFORM_FAILURE = "transformFailure";
    /** A method whose entry stack map frame could not be built safely (read-then-store only). */
    public static final String SKIP_FRAME_EMISSION_UNSUPPORTED = "frameEmissionUnsupported";
    /** The class's loader cannot see the agent jar and the java.lang bridge is not installed. */
    public static final String SKIP_AGENT_NOT_VISIBLE = "agentNotVisible";
    /** Counted once per JVM when java.lang.$Auxin could not be installed. */
    public static final String SKIP_BRIDGE_UNAVAILABLE = "bridgeUnavailable";
    /** An interface needs the static-field fallback, which an interface cannot hold. */
    public static final String SKIP_INTERFACE_NEEDS_FIELD = "interfaceNeedsField";
    /**
     * F2: the self-BSM bridge shape is structurally impossible for this class — the class
     * already declares a member with the synthetic bootstrap method's name and descriptor, so
     * adding ours would either collide or silently retarget the condy. Skip and count; never
     * emit a condy whose bootstrap method we did not author.
     */
    public static final String SKIP_BRIDGE_BSM_UNAVAILABLE = "bridgeBsmUnavailable";
    /** A tier-2 method in a class whose loader cannot see the agent: tier-1 only, never broken. */
    public static final String SKIP_TIER2_NOT_BRIDGEABLE = "tier2NotBridgeable";
    /**
     * {@code java.lang.$Auxin.data} no longer holds our runtime object, so the bridge
     * prologue would break the class's {@code <clinit>}. Skip and count (isolation suite F3).
     */
    public static final String SKIP_BRIDGE_TAMPERED = "bridgeTampered";
    /**
     * A class rejected by {@code IgnoreRules} — the JDK, our own runtime, or another agent's.
     * Counted only for classes the operator's {@code ax.include.packages} actually asked for:
     * the {@code java/*} rejections are not in scope and counting them would drown the signal
     * (G5-BUG-1). Every rejection that reaches this counter is therefore actionable.
     */
    public static final String SKIP_IGNORED_PREFIX = "ignoredPrefix";
    /**
     * The loud half of {@link #SKIP_IGNORED_PREFIX}: a class that matched
     * {@code ax.include.packages} and was then vetoed by a name filter. An explicitly requested
     * class that we refuse to instrument is the C37 failure mode; it gets its own counter and
     * one WARN per JVM so it can never read as a clean run.
     */
    public static final String SKIP_IN_SCOPE_VETOED = "inScopeVetoed";

    private static final AtomicLong TRANSFORM_FAILURES = new AtomicLong();
    private static final AtomicLong CLASSES_INSTRUMENTED = new AtomicLong();
    private static final AtomicLong CLASSES_STRIPPED = new AtomicLong();
    private static final AtomicLong STRIP_FAILURES = new AtomicLong();
    private static final AtomicLong STRIPS_BLOCKED = new AtomicLong();
    private static final AtomicLong STRIP_REARMS = new AtomicLong();
    private static final AtomicLong FLUSH_FAILURES = new AtomicLong();
    private static final AtomicLong FLUSHES_OK = new AtomicLong();
    private static final AtomicLong PROBES_INSTALLED = new AtomicLong();
    private static final AtomicLong TIER2_INSTALLED = new AtomicLong();

    /** Hot path: incremented on an application thread when the ring rejects an event. */
    public static final LongAdder RING_DROPPED = new LongAdder();

    private static final ConcurrentHashMap<String, AtomicLong> SKIPPED =
            new ConcurrentHashMap<String, AtomicLong>();

    private static volatile boolean degraded;
    private static volatile String degradedReason = "";

    public static void skip(String reason) {
        counter(reason).incrementAndGet();
    }

    public static void skip(String reason, long n) {
        counter(reason).addAndGet(n);
    }

    private static AtomicLong counter(String reason) {
        AtomicLong c = SKIPPED.get(reason);
        if (c == null) {
            c = new AtomicLong();
            AtomicLong prev = SKIPPED.putIfAbsent(reason, c);
            if (prev != null) c = prev;
        }
        return c;
    }

    public static void transformFailure() { TRANSFORM_FAILURES.incrementAndGet(); }
    public static void classInstrumented(int probes, int tier2) {
        CLASSES_INSTRUMENTED.incrementAndGet();
        PROBES_INSTALLED.addAndGet(probes);
        TIER2_INSTALLED.addAndGet(tier2);
    }
    public static void classStripped() { CLASSES_STRIPPED.incrementAndGet(); }
    public static void stripFailure() { STRIP_FAILURES.incrementAndGet(); }

    /**
     * A strip that removed nothing (G5-BUG-3).
     *
     * <p>Its own counter, for two reasons. It is deliberately NOT {@link #stripFailure()} —
     * that one means "the retransform threw", and conflating them would hide whichever is
     * actually happening. And it is deliberately NOT a {@code classesSkipped{reason}} entry
     * either: that map means "classes we did not instrument", and a class whose probes are all
     * present and firing is the opposite of skipped. {@code classesStripped} must not be
     * incremented alongside this.
     */
    public static void stripBlocked() { STRIPS_BLOCKED.incrementAndGet(); }

    /**
     * A class we believed de-instrumented had its probes replayed by a foreign agent's
     * retransform, so the {@code stripped} decision was revoked and one more attempt allowed
     * (G5 section 7). Correctness was never affected; the zero-overhead claim was.
     */
    public static void stripReArm() { STRIP_REARMS.incrementAndGet(); }
    public static void flushOk() { FLUSHES_OK.incrementAndGet(); }
    public static void flushFailure() { FLUSH_FAILURES.incrementAndGet(); }

    public static long transformFailures() { return TRANSFORM_FAILURES.get(); }
    public static long classesInstrumented() { return CLASSES_INSTRUMENTED.get(); }
    public static long classesStripped() { return CLASSES_STRIPPED.get(); }
    public static long stripFailures() { return STRIP_FAILURES.get(); }
    public static long stripsBlocked() { return STRIPS_BLOCKED.get(); }
    public static long stripReArms() { return STRIP_REARMS.get(); }
    public static long flushFailures() { return FLUSH_FAILURES.get(); }
    public static long flushesOk() { return FLUSHES_OK.get(); }
    public static long probesInstalled() { return PROBES_INSTALLED.get(); }
    public static long tier2Installed() { return TIER2_INSTALLED.get(); }
    public static long ringDropped() { return RING_DROPPED.sum(); }

    /** Snapshot of ax_classes_skipped_total{reason}. */
    public static Map<String, Long> skipped() {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, AtomicLong> e : SKIPPED.entrySet()) {
            out.put(e.getKey(), Long.valueOf(e.getValue().get()));
        }
        return out;
    }

    /**
     * A degraded window must never be used as evidence of death (CONTRACTS section 2).
     * Latching: once degraded, the JVM stays degraded for its lifetime.
     */
    public static void degrade(String reason) {
        if (!degraded) degradedReason = reason;
        degraded = true;
    }

    public static boolean degraded() { return degraded; }
    public static String degradedReason() { return degradedReason; }

    /**
     * One-shot latch for a WARN that must be emitted exactly once per JVM.
     *
     * <p>Both halves matter. An agent that chatters in production gets uninstalled, so a
     * per-class WARN is not an option on a path that can fire thousands of times; and silence is
     * the C37 failure mode, so "count it and say nothing" is not an option either. The counters
     * carry the volume, this carries the one human-readable line.
     *
     * @return true the first time this key is seen, false afterwards.
     */
    public static boolean warnOnce(String key) {
        return WARNED_ONCE.putIfAbsent(key, Boolean.TRUE) == null;
    }

    private static final ConcurrentHashMap<String, Boolean> WARNED_ONCE =
            new ConcurrentHashMap<String, Boolean>();

    private Health() { throw new AssertionError(); }
}
