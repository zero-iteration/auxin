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
    /**
     * A method whose shape the call-edge tier cannot instrument: a {@code jsr}/{@code ret}
     * subroutine, a boundary method that is a constructor (the handler would have to merge an
     * {@code uninitializedThis} frame), or a boundary method whose frames could not be read
     * expanded. Tier-1 and tier-2 are unaffected for that method; it simply records no edges.
     */
    public static final String SKIP_EDGE_UNSUPPORTED = "edgeUnsupported";
    /**
     * A class whose loader cannot see the agent jar. The edge tier emits a direct
     * {@code EdgeRuntime} call for the same reason tier-2 does, and for the same reason it
     * cannot be bridged: any {@code Object.equals} hop allocates per invocation. Tier-1 still
     * works through the bridge; edges degrade off, never to a NoClassDefFoundError.
     */
    public static final String SKIP_EDGES_NOT_BRIDGEABLE = "edgesNotBridgeable";
    /** The edge tier's 24-bit id space is exhausted (16.7M instrumented methods). */
    public static final String SKIP_EDGE_ID_EXHAUSTED = "edgeIdSpaceExhausted";

    private static final AtomicLong TRANSFORM_FAILURES = new AtomicLong();
    private static final AtomicLong CLASSES_INSTRUMENTED = new AtomicLong();
    private static final AtomicLong CLASSES_STRIPPED = new AtomicLong();
    private static final AtomicLong STRIP_FAILURES = new AtomicLong();
    private static final AtomicLong STRIPS_BLOCKED = new AtomicLong();
    private static final AtomicLong STRIP_REARMS = new AtomicLong();
    private static final AtomicLong STRIP_MASK_MISSING = new AtomicLong();
    private static final AtomicLong FLUSH_FAILURES = new AtomicLong();
    private static final AtomicLong FLUSHES_OK = new AtomicLong();
    private static final AtomicLong PROBES_INSTALLED = new AtomicLong();
    private static final AtomicLong TIER2_INSTALLED = new AtomicLong();

    /** Hot path: incremented on an application thread when the ring rejects an event. */
    public static final LongAdder RING_DROPPED = new LongAdder();

    // ---- call-edge tier (SCOPE-v3) ----
    // All four are touched on an application thread, and all four only on a SAMPLED path:
    // once per sampled root invocation, or once per root invocation whose caps bit. At the
    // default 1-in-1024 that is three orders of magnitude below the per-call rate, which is why
    // a LongAdder is affordable here and is not affordable in EdgeRuntime.record().
    /** Root invocations that were sampled. The denominator for every edge count on the wire. */
    public static final LongAdder EDGE_ROOTS_SAMPLED = new LongAdder();
    /** The edge ring rejected an event (drop-on-full, C27). */
    public static final LongAdder EDGE_RING_DROPPED = new LongAdder();
    /** Root invocations that hit {@code ax.edges.max.depth}. Counted once per invocation. */
    public static final LongAdder EDGES_TRUNCATED_DEPTH = new LongAdder();
    /** Root invocations that hit {@code ax.edges.max.per.root}. Counted once per invocation. */
    public static final LongAdder EDGES_TRUNCATED_ROOT = new LongAdder();

    private static final AtomicLong EDGE_TIER_FAILURES = new AtomicLong();
    private static final AtomicLong EDGE_TRACES_REAPED = new AtomicLong();

    private static final ConcurrentHashMap<String, AtomicLong> SKIPPED =
            new ConcurrentHashMap<String, AtomicLong>();

    private static volatile boolean degraded;
    private static volatile String degradedReason = "";

    // ---- Tier-1b / trace overlap (SCOPE-v3.1) ----
    /**
     * Tier-1b's auto-strip is suppressed for the intersection of the traced and instrumented
     * scopes. Set once at premain and never cleared.
     *
     * <p>This exists because the failure mode being prevented is a <b>true counter and a false
     * conclusion</b>: without it, {@code classesStripped} rises for classes whose hot path still
     * carries trace probes, and an operator reads "overhead is now zero" and is wrong. Taking the
     * trade is fine; taking it silently is not, so it ships on the wire next to the count of
     * classes it actually affected.
     */
    private static volatile boolean TIER1B_DISABLED_BY_TRACE;

    /** The scope intersection responsible, for the startup line and the wire. */
    private static volatile String TIER1B_TRACE_SCOPE = "";

    /**
     * Distinct classes Tier-1b declined to strip because they are traced. Bounded: past the cap
     * the count keeps rising and the name set stops growing, because this is a diagnostic and
     * not a reason to hold a million strings.
     */
    private static final ConcurrentHashMap<String, Boolean> TIER1B_TRACE_BLOCKED =
            new ConcurrentHashMap<String, Boolean>();
    private static final AtomicLong TIER1B_TRACE_BLOCKED_COUNT = new AtomicLong();
    private static final int TIER1B_TRACE_BLOCKED_CAP = 10000;

    /** Records the premain decision. Called once, whether or not any class ever hits it. */
    public static void tier1bDisabledByTrace(String scopeDescription) {
        TIER1B_TRACE_SCOPE = scopeDescription == null ? "" : scopeDescription;
        TIER1B_DISABLED_BY_TRACE = true;
    }

    /**
     * One class Tier-1b was asked about and refused, because it is traced. Idempotent per class:
     * the drain thread asks again on every cycle and this must not count the same class twice.
     */
    public static void tier1bStripBlocked(String dottedClassName) {
        if (TIER1B_TRACE_BLOCKED.size() >= TIER1B_TRACE_BLOCKED_CAP) return;
        if (TIER1B_TRACE_BLOCKED.putIfAbsent(dottedClassName, Boolean.TRUE) == null) {
            TIER1B_TRACE_BLOCKED_COUNT.incrementAndGet();
        }
    }

    public static boolean tier1bDisabledByTrace() { return TIER1B_DISABLED_BY_TRACE; }
    public static String tier1bTraceScope() { return TIER1B_TRACE_SCOPE; }
    public static long tier1bTraceBlockedClasses() { return TIER1B_TRACE_BLOCKED_COUNT.get(); }

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

    /**
     * Tier-1b could not decide whether a class is strippable, so it left the probes installed.
     *
     * <p>Raised when a class has a probe array but no usable installed-probe mask, or one whose
     * length disagrees with the array. Both are structurally impossible — the mask is recorded
     * before the array, from the same manifest {@code probeCount} — which is precisely why the
     * case gets a counter instead of an assumption: the fail-open answer is "keep the probes",
     * and a silent fail-open is how the zero-overhead claim went wrong in the first place.
     *
     * <p>Its own counter, not {@link #stripBlocked()} (which means "the retransform ran and
     * removed nothing") and not a {@code classesSkipped{reason}} entry (which means "we did not
     * instrument this class" — the opposite of a class whose probes are all present). Cost only;
     * coverage is unaffected.
     */
    public static void stripMaskMissing() { STRIP_MASK_MISSING.incrementAndGet(); }
    public static void flushOk() { FLUSHES_OK.incrementAndGet(); }
    public static void flushFailure() { FLUSH_FAILURES.incrementAndGet(); }

    public static long transformFailures() { return TRANSFORM_FAILURES.get(); }
    public static long classesInstrumented() { return CLASSES_INSTRUMENTED.get(); }
    public static long classesStripped() { return CLASSES_STRIPPED.get(); }
    public static long stripFailures() { return STRIP_FAILURES.get(); }
    public static long stripsBlocked() { return STRIPS_BLOCKED.get(); }
    public static long stripReArms() { return STRIP_REARMS.get(); }
    public static long stripMasksMissing() { return STRIP_MASK_MISSING.get(); }
    public static long flushFailures() { return FLUSH_FAILURES.get(); }
    public static long flushesOk() { return FLUSHES_OK.get(); }
    public static long probesInstalled() { return PROBES_INSTALLED.get(); }
    public static long tier2Installed() { return TIER2_INSTALLED.get(); }
    public static long ringDropped() { return RING_DROPPED.sum(); }

    /**
     * The edge tier latched itself off (EdgeRuntime.fail). Deliberately NOT
     * {@link #degrade(String)}: coverage, tier-2 and the strip are untouched, so a window with
     * this counter set is still valid evidence about everything except edges. Conflating the two
     * would throw away good coverage data because a convenience tier misbehaved.
     */
    public static void edgeTierFailure() { EDGE_TIER_FAILURES.incrementAndGet(); }

    /** The drain thread reset a leaked edge-trace gate (EdgeRuntime.reapStaleTraces). */
    public static void edgeTraceReaped() { EDGE_TRACES_REAPED.incrementAndGet(); }

    public static long edgeTierFailures() { return EDGE_TIER_FAILURES.get(); }
    public static long edgeTracesReaped() { return EDGE_TRACES_REAPED.get(); }
    public static long edgeRootsSampled() { return EDGE_ROOTS_SAMPLED.sum(); }
    public static long edgeRingDropped() { return EDGE_RING_DROPPED.sum(); }
    public static long edgesTruncatedDepth() { return EDGES_TRUNCATED_DEPTH.sum(); }
    public static long edgesTruncatedRoot() { return EDGES_TRUNCATED_ROOT.sum(); }

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
