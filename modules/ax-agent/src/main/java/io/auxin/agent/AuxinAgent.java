package io.auxin.agent;

import io.auxin.agent.config.EnvironmentClassification;
import io.auxin.agent.config.Options;
import io.auxin.agent.health.Health;
import io.auxin.agent.instrument.IgnoreRules;
import io.auxin.agent.instrument.ProbeInstaller;
import io.auxin.agent.instrument.ProbeStripper;
import io.auxin.agent.instrument.StartupBudget;
import io.auxin.agent.manifest.Manifest;
import io.auxin.agent.runtime.BootstrapBridge;
import io.auxin.agent.runtime.Clock;
import io.auxin.agent.runtime.DrainThread;
import io.auxin.agent.runtime.EdgeAggregator;
import io.auxin.agent.runtime.EdgeRegistry;
import io.auxin.agent.runtime.EdgeRuntime;
import io.auxin.agent.runtime.ProbeHolder;
import io.auxin.agent.runtime.Ring;
import io.auxin.agent.runtime.Tier2Aggregator;
import io.auxin.agent.runtime.Tier2Runtime;
import io.auxin.agent.transport.HttpSender;
import io.auxin.agent.util.Log;

import java.lang.instrument.Instrumentation;

/**
 * Agent entry point.
 *
 * <p>Wiring, in the order it has to happen:
 * <pre>
 *   options -> manifest -> clock calibration -> ring + tier-2 runtime
 *           -> ProbeInstaller  (addTransformer(t))        canRetransform = FALSE
 *           -> ProbeStripper   (addTransformer(t, true))  canRetransform = TRUE
 *           -> drain thread (daemon)
 * </pre>
 *
 * <p>Every step is wrapped: if anything at all goes wrong the agent does nothing and the
 * application starts normally. There is no failure mode here worth an outage.
 */
public final class AuxinAgent {

    private static volatile Instrumentation instrumentation;
    private static volatile Options options;
    private static volatile Manifest manifest;
    private static volatile DrainThread drain;
    private static volatile ProbeStripper stripper;
    private static volatile boolean active;

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            init(agentArgs, inst);
        } catch (Throwable t) {
            // FAIL OPEN. A JVM that starts without coverage is a bad window;
            // a JVM that does not start is an outage.
            Health.degrade("premainFailed");
            Log.warn("premain failed, agent disabled for this JVM: " + t, t);
        }
    }

    /** Dynamic attach is deliberately NOT supported: probes install at initial class load only. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        Log.warn("dynamic attach is not supported (probes install at initial class load only, "
                + "VALIDATION C1). Use -javaagent.");
    }

    private static void init(String agentArgs, Instrumentation inst) {
        long t0 = System.nanoTime();
        instrumentation = inst;
        Options o = Options.parse(agentArgs);
        options = o;

        if (!o.enabled) {
            Log.info("disabled by kill switch (ax.enabled=false)");
            return;
        }
        if (o.scope.empty()) {
            Log.info("ax.include.packages is unset: instrumenting nothing (default-deny). "
                    + "Set ax.include.packages=com.yourcompany to enable.");
            return;
        }

        Manifest m = Manifest.load(o.manifestPath);
        if (m == null) {
            Log.warn("no build manifest found (ax.manifest=" + (o.manifestPath.isEmpty()
                    ? "<unset>" : o.manifestPath) + "): instrumenting nothing. Probe indices come "
                    + "from the build-time manifest and are never derived at runtime (C7).");
            return;
        }
        if (m.schemaVersion() != 1 && m.schemaVersion() != 2) {
            Log.warn("manifest schemaVersion=" + m.schemaVersion() + ", expected 1 or 2: "
                    + "instrumenting nothing. Probe indices from an unknown manifest schema "
                    + "would be silently misattributed (A14 defect 2).");
            return;
        }
        if (m.lacksObservabilityFlags()) {
            Log.warn("manifest carries no dynamicallyObservable flags (C51). CONTRACTS section 1 "
                    + "assumes 'absent => false', so the collector will treat every probe in this "
                    + "build as not dynamically observable and produce ZERO dead candidates. "
                    + "Regenerate the manifest with a ax-static that emits the C51/C59 flags.");
        }
        manifest = m;

        // Before any transformer is registered: an instrumented class must never be handed a
        // reference it cannot resolve, so the bridge's availability has to be known first.
        if (o.bridgeEnabled) {
            BootstrapBridge.install(inst);
        } else {
            Log.info("bootstrap bridge disabled (ax.bridge.enabled=false): classes whose loader "
                    + "cannot see the agent jar will be skipped");
        }

        EnvironmentClassification env = EnvironmentClassification.detect(o);

        Clock clock = Clock.calibrate(o.clockSampledThresholdNs, o.clockDisabledThresholdNs);
        // BUG #30 (owner's call, escalated by the #25 fix): a slow clock must NOT degrade the
        // window. `degraded: true` means "this window's coverage is untrustworthy" and CONTRACTS
        // discards such a window as death evidence -- but a slow clock affects ONLY the latency
        // buckets. Coverage bits and call counts are computed without the clock at all.
        //
        // Worse than over-broad: the field trial showed the clock decision is jittery near its
        // threshold ("same machine, two boots: one disabled, the next sampled"), so coupling them
        // made a boot-to-boot coin flip silently discard a whole window's COVERAGE -- and coverage
        // is the substrate the entire dead-code verdict rests on. Non-deterministic evidence loss
        // for an unrelated reason.
        //
        // Nothing is hidden by decoupling: `clockNs`, `clockDegraded` and (since #25)
        // `tier2TimingMode` all still ship, so a reader can see exactly what was lost -- the
        // percentiles, and only the percentiles. Same argument that keeps `edgeTierFailures` out
        // of `degraded`.
        if (clock.degraded()) {
            Log.warn("clock too slow for tier-2 timing (clockNs=" + clock.nanosPerCall()
                    + "): percentiles are unavailable. Coverage, calls and errors are UNAFFECTED "
                    + "and this window is NOT degraded.");
        }

        Ring ring = null;
        Tier2Aggregator aggregator = new Tier2Aggregator();
        if (o.tier2Enabled) {
            ring = new Ring(o.ringCapacity);
            Tier2Runtime.install(ring, clock, true);
        }

        // The edge tier gets its own pre-allocated ring (A7: nothing on this path may allocate,
        // and two 24-bit ids do not fit into tier-2's packed event). Separate rings also keep a
        // burst of edges from a sampled trace from evicting tier-2 events, which are the
        // load-bearing rate and latency numbers under SCOPE-v3.
        Ring edgeRing = null;
        EdgeAggregator edgeAggregator = null;
        if (o.edgesEnabled) {
            edgeRing = new Ring(o.edgesRingCapacity);
            edgeAggregator = new EdgeAggregator(o.edgesMaxDistinct);
            EdgeRuntime.install(edgeRing, o.edgesSampleRate, o.edgesMaxDepth,
                    o.edgesMaxPerRoot, true);
        }

        // Before the transformer is registered, for two reasons. (1) It loads IgnoreRules here,
        // where the class can be resolved normally — the alternative is resolving it from inside
        // the very first transform, which is a recursive load of a class the transformer needs.
        // (2) An include scope that overlaps the agent's own runtime is the only case where
        // "in scope but never instrumentable" is expected, so it is said once, now, instead of
        // per class from a path that must not touch agent classes at all (G5-BUG-1).
        if (o.scope.included(IgnoreRules.agentRuntime())
                || o.scope.included(IgnoreRules.asmRuntime())) {
            Log.warn("ax.include.packages=" + o.scope.includeDescription() + " also matches the "
                    + "agent's own runtime (" + IgnoreRules.agentRuntime() + " / "
                    + IgnoreRules.asmRuntime() + "). Those classes are never instrumented and are "
                    + "not reported as skips. Narrow the scope to your application's packages.");
        }

        StartupBudget budget = new StartupBudget(o.startupCpuBudgetMs, o.startupWallBudgetMs);
        ProbeStripper strip = new ProbeStripper();
        stripper = strip;

        // ORDER MATTERS AND IS LOAD BEARING (E1):
        // incapable first -> runs ahead of OTel and its output is replayed on every later
        // retransform, so our probes survive other agents' retransform batches.
        inst.addTransformer(new ProbeInstaller(o, m, budget));
        inst.addTransformer(strip, true);

        HttpSender sender = new HttpSender(o);
        DrainThread d = new DrainThread(o, m, ring, aggregator, edgeRing, edgeAggregator,
                sender, strip, inst, clock, env);
        drain = d;
        d.start();

        active = true;
        long ms = (System.nanoTime() - t0) / 1000000L;
        Log.info("armed in " + ms + "ms: " + o.summary()
                + " manifestClasses=" + m.classCount()
                + " buildSha=" + m.buildSha()
                + " bridge=" + BootstrapBridge.status()
                // G5-BUG-1: the prefixes that can veto the operator's own scope, printed so
                // "the agent instrumented nothing" is diagnosable from the startup line alone.
                + " agentRuntime=" + IgnoreRules.agentRuntime()
                // BUG #25: the timing mode and its consequence belong on the line an operator
                // actually reads. "tier2TimingMode=disabled" alone reads as "tier-2 is dead on
                // this host"; it means "no percentiles, counts unaffected", and that is the
                // difference between ignoring the agent and ripping it out.
                + " tier2TimingMode=" + clock.wireMode()
                + " (" + clock.consequence() + ")"
                + " livenessEvidence=" + env.livenessEvidence());
    }

    // ---------------- ops / test hooks ----------------

    public static boolean active() { return active; }

    public static Options options() { return options; }

    public static Manifest manifest() { return manifest; }

    public static Instrumentation instrumentation() { return instrumentation; }

    /** Tier-1b on demand. Used by the smoke test and available as a break-glass control. */
    public static boolean stripNow(Class<?> c) {
        DrainThread d = drain;
        return d != null && d.stripNow(c);
    }

    /**
     * Does Tier-1b believe this class is de-instrumented right now? Ops/test hook.
     *
     * <p>The only way to observe the AUTOMATIC strip per class: {@code classesStripped} is a
     * JVM-wide counter of operations, so it cannot answer "did THIS class get stripped". Not a
     * latch — a foreign retransform that replays our probes revokes it (G5 section 7).
     */
    public static boolean stripped(String dottedClassName) {
        DrainThread d = drain;
        return d != null && d.isStripped(dottedClassName);
    }

    /** Which probe indices of a class actually carry a probe. Ops/test hook; null when unknown. */
    public static boolean[] installedProbes(String dottedClassName) {
        return ProbeHolder.installedProbes(dottedClassName);
    }

    /** Forces one window out of band. @return the JSON body that was built. */
    public static String flushNow() {
        DrainThread d = drain;
        return d == null ? "" : d.flush();
    }

    /** Is the runtime call-edge tier armed in this JVM? Ops/test hook. */
    public static boolean edgesEnabled() { return EdgeRuntime.enabled(); }

    /**
     * Sampled edge traces currently in flight. Ops/test hook, and the one number that proves the
     * tier is not leaking: it must return to 0 after every root invocation, including the ones
     * an exception left through.
     */
    public static int edgeActiveTraces() { return EdgeRuntime.activeTraces(); }

    /** Methods that carry call-edge instrumentation in this JVM. Ops/test hook. */
    public static int edgeIdsRegistered() { return EdgeRegistry.size(); }

    /**
     * Latches the call-edge tier off for the life of this JVM, down the same path an unexpected
     * Throwable takes. Break-glass control, and the only way to verify the fail-open latch,
     * its counter and its one-shot WARN actually work.
     */
    public static void edgesForceFailOpen(String why) { EdgeRuntime.forceFailOpen(why); }

    /** Is {@code java.lang.$Auxin} installed in this JVM? Ops/test hook. */
    public static boolean bridgeInstalled() { return BootstrapBridge.installed(); }

    /** Why the bridge is (not) available. Ops/test hook. */
    public static String bridgeStatus() { return BootstrapBridge.status(); }

    /** The accumulated probe array for a class, or null when it was never instrumented. */
    public static boolean[] probes(String dottedClassName) {
        return ProbeHolder.peek(dottedClassName);
    }

    /** Manifest probe index for one method, or -1. Never derived from visit order. */
    public static int probeIndex(String dottedClassName, String methodName, String desc) {
        Manifest m = manifest;
        if (m == null) return -1;
        Manifest.ClassEntry e = m.byInternalName(dottedClassName.replace('.', '/'));
        if (e == null) return -1;
        Manifest.MethodEntry me = e.method(methodName, desc);
        return me == null ? -1 : me.idx;
    }

    private AuxinAgent() { throw new AssertionError(); }
}
