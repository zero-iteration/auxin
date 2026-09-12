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
import io.auxin.trace.config.Projection;
import io.auxin.trace.config.StripConflict;
import io.auxin.trace.config.TraceOptions;
import io.auxin.trace.runtime.Observations;
import io.auxin.trace.runtime.RateCap;
import io.auxin.trace.runtime.Redaction;
import io.auxin.trace.runtime.SiteRegistry;
import io.auxin.trace.runtime.TraceGate;
import io.auxin.trace.runtime.TraceRuntime;
import io.auxin.trace.transport.TraceDispatcher;
import io.auxin.trace.transport.TraceDocument;
import io.auxin.trace.transport.TraceSender;
import io.auxin.trace.util.TLog;

import java.lang.instrument.Instrumentation;

/**
 * Agent entry point.
 *
 * <p><b>One premain, one jar (SCOPE-v3.1).</b> The per-request tracer used to ship as its own
 * {@code -javaagent} with its own {@code TraceAgent.premain}. The owner took the trade
 * explicitly — <i>"we can have one agent only, its fine if it adds overhead for some
 * requests"</i> — so it is a tier here now, off by default, armed by
 * {@code ax.trace.enabled=true}.
 *
 * <p>Wiring, in the order it has to happen:
 * <pre>
 *   options -> manifest -> clock calibration -> ring + tier-2 runtime
 *           -> TRACE TIER: master switch -> scope -> production token
 *                          -> Tier-1b overlap decision -> projection/redaction
 *                          -> dispatcher -> TraceRuntime -> TraceGate
 *           -> WARM THE WHOLE TRANSFORM PATH (a complete ASM round trip)
 *           -> ProbeInstaller  (addTransformer(t))        canRetransform = FALSE
 *           -> ProbeStripper   (addTransformer(t, true))  canRetransform = TRUE
 *           -> drain thread (daemon)
 * </pre>
 *
 * <p><b>The warm-up is not optional and it is not a performance tweak.</b> A class reached for
 * the first time from inside {@code transform()} is a re-entrant load on the same thread, which
 * the JVM answers with {@code LinkageError: attempted duplicate class definition} — and it
 * presented as "premain failed" AFTER the transformer had been registered, i.e. a half-armed
 * agent. So every class on the transform path, ASM's reader/writer/tree included, is resolved by
 * running a complete round trip BEFORE {@code addTransformer}. See
 * {@link ProbeInstaller#warmUp(byte[])}.
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

    // ---- the trace tier (SCOPE-v3.1). All null / false unless ax.trace.enabled=true. ----
    private static volatile TraceDispatcher traceDispatcher;
    private static volatile TraceSender traceSender;
    private static volatile StripConflict stripConflict = StripConflict.inert();
    private static volatile boolean traceActive;

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
        // COVERAGE ARMING, in three gates. Each one used to `return` -- and each one still says
        // exactly what it always said, because the negative suite reads these lines. What changed
        // is that they no longer end the premain: the trace tier has its OWN default-deny scope
        // and needs no manifest, so `ax.include.packages is unset` must not silently disarm a
        // tracer the operator switched on. `coverage` carries the decision instead of `return`.
        boolean coverage = true;

        if (o.scope.empty()) {
            Log.info("ax.include.packages is unset: instrumenting nothing (default-deny). "
                    + "Set ax.include.packages=com.yourcompany to enable.");
            coverage = false;
        }

        Manifest m = coverage ? Manifest.load(o.manifestPath) : null;
        if (coverage && m == null) {
            Log.warn("no build manifest found (ax.manifest=" + (o.manifestPath.isEmpty()
                    ? "<unset>" : o.manifestPath) + "): instrumenting nothing. Probe indices come "
                    + "from the build-time manifest and are never derived at runtime (C7).");
            coverage = false;
        }
        if (coverage && m.schemaVersion() != 1 && m.schemaVersion() != 2) {
            Log.warn("manifest schemaVersion=" + m.schemaVersion() + ", expected 1 or 2: "
                    + "instrumenting nothing. Probe indices from an unknown manifest schema "
                    + "would be silently misattributed (A14 defect 2).");
            coverage = false;
            m = null;
        }
        if (coverage && m.lacksObservabilityFlags()) {
            Log.warn("manifest carries no dynamicallyObservable flags (C51). CONTRACTS section 1 "
                    + "assumes 'absent => false', so the collector will treat every probe in this "
                    + "build as not dynamically observable and produce ZERO dead candidates. "
                    + "Regenerate the manifest with a ax-static that emits the C51/C59 flags.");
        }
        manifest = m;

        // THE TRACE TIER. Decided before anything is registered, because its Tier-1b overlap
        // decision has to be in force before the first class can be considered for a strip, and
        // because a tracer that refuses to arm must do so without leaving anything half-wired.
        final boolean trace = initTraceTier(o, coverage);

        if (!coverage && !trace) {
            // Nothing is armed. Say so once and leave the JVM byte-for-byte as it was.
            Log.info("nothing to do: coverage is not armed and ax.trace.enabled=false. No "
                    + "transformer is registered and this JVM is byte-for-byte what it would be "
                    + "without -javaagent.");
            return;
        }

        // Before any transformer is registered: an instrumented class must never be handed a
        // reference it cannot resolve, so the bridge's availability has to be known first.
        if (coverage && o.bridgeEnabled) {
            BootstrapBridge.install(inst);
        } else if (coverage) {
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
        if (coverage && o.tier2Enabled) {
            ring = new Ring(o.ringCapacity);
            Tier2Runtime.install(ring, clock, true);
        }

        // The edge tier gets its own pre-allocated ring (A7: nothing on this path may allocate,
        // and two 24-bit ids do not fit into tier-2's packed event). Separate rings also keep a
        // burst of edges from a sampled trace from evicting tier-2 events, which are the
        // load-bearing rate and latency numbers under SCOPE-v3.
        Ring edgeRing = null;
        EdgeAggregator edgeAggregator = null;
        if (coverage && o.edgesEnabled) {
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

        ProbeInstaller installer = new ProbeInstaller(o, m, budget, trace);

        // BEFORE addTransformer, ALWAYS. The tracer's own smoke suite found this the hard way:
        //   LinkageError: loader 'app' attempted duplicate class definition for ...
        // The startup line below touches classes on the transform path. If one of them is first
        // reached AFTER addTransformer, the app loader begins defining it, the JVM calls our
        // transformer for it, and the transformer's first statement is a re-entrant load of the
        // class being defined, on the same thread. It presented as "premain failed" AFTER the
        // transformer had been registered -- a half-armed agent.
        //
        // Naming the classes is not enough; warmUp runs a COMPLETE ASM round trip so the reader,
        // the writer and every tree node are genuinely loaded. Folding the tracer in makes this
        // strictly more necessary, not less: there are more classes on the path now.
        installer.warmUp(ownClassBytes());

        // ORDER MATTERS AND IS LOAD BEARING (E1):
        // incapable first -> runs ahead of OTel and its output is replayed on every later
        // retransform, so our probes survive other agents' retransform batches.
        //
        // ONE PAIR, AND ONLY ONE. The startup-cost research (A11/G4) measured a 20x regression
        // from N transformers each scanning the class hierarchy, which is why the trace tier is
        // emitted from ProbeInstaller rather than registering a third transformer of its own.
        inst.addTransformer(installer);
        inst.addTransformer(strip, true);

        if (coverage) {
            HttpSender sender = new HttpSender(o);
            DrainThread d = new DrainThread(o, m, ring, aggregator, edgeRing, edgeAggregator,
                    sender, strip, stripConflict, inst, clock, env);
            drain = d;
            d.start();
        }

        active = coverage;
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
                + " livenessEvidence=" + env.livenessEvidence()
                + " coverage=" + coverage
                // SCOPE-v3.1: the two facts an operator needs in order to read classesStripped
                // correctly, on the line they already read. `tier1bDisabledByTrace=true` is the
                // answer to "why is steady-state overhead not zero on this JVM".
                + " trace=" + trace
                + (trace ? " " + stripConflict.summary() : ""));
    }

    /** The agent's own class file, used to warm the whole transform path with a real round trip. */
    private static byte[] ownClassBytes() {
        java.io.InputStream in = null;
        try {
            in = AuxinAgent.class.getClassLoader()
                    .getResourceAsStream("io/auxin/agent/AuxinAgent.class");
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }

    /**
     * Arms the per-request trace tier, or explains why it is not armed.
     *
     * <p>Every refusal here is a {@code return false} with a line said out loud, because the one
     * thing a tracer must never be is quietly off — "the tracer recorded nothing" has to be
     * distinguishable from "nobody asked it to" (PLAN-v2 C37).
     *
     * @param coverageArmed is the coverage side of the agent armed? Only then can Tier-1b strip
     *                      anything, so only then is there an overlap to resolve.
     * @return true when the tier armed and classes will carry trace probes.
     */
    private static boolean initTraceTier(Options o, boolean coverageArmed) {
        final TraceOptions t = o.trace;

        // ---- 1. the master switch ----
        if (!t.enabled) {
            // One line, at info, and then silence. This is the DEFAULT state, so it must be
            // cheap to say and unmistakable to read.
            TLog.info("per-request tracer is OFF (ax.trace.enabled=false, the default). "
                    + "Nothing is instrumented for tracing: no entry probe, no frame probe, no "
                    + "branch probe. Every other tier is unaffected and Tier-1b still strips to "
                    + "zero.");
            return false;
        }

        // ---- 2. default deny ----
        if (t.scope.empty() && t.entryScope.empty()) {
            TLog.warn("ax.trace.enabled=true but ax.trace.include.packages is unset: tracing "
                    + "nothing (default-deny). Set ax.trace.include.packages=com.yourcompany. "
                    + "A trace probe records values, so there is no instrument-everything "
                    + "switch in this tier and there is not going to be one.");
            return false;
        }

        // ---- 3. an unauthenticated trace header in production ----
        if (t.tokenRequiredButMissing()) {
            TLog.warn("REFUSING TO ARM: ax.environment=" + t.environment + " is classified as "
                    + "production and ax.trace.token is unset."
                    + "\n  An unauthenticated " + t.headerName + " header is two things at once:"
                    + "\n    - an AMPLIFICATION vector: one header makes the service do 10-100x "
                    + "the work of the request, and any caller can set it;"
                    + "\n    - a DATA-EXPOSURE vector: any caller can ask for a structural trace "
                    + "of the code path their request took."
                    + "\n  Set ax.trace.token=<a shared secret> (the header value must then equal "
                    + "it), or classify this JVM as non-production if it is not one.");
            return false;
        }

        // ---- 4. THE TIER-1b OVERLAP. Taken by default now, not refused. ----
        final StripConflict c = coverageArmed ? StripConflict.detect(o) : StripConflict.inert();
        stripConflict = c;
        if (coverageArmed && !c.armTracer) {
            // StripConflict.detect has already printed the full explanation and the ways out.
            return false;
        }
        if (c.tier1bDisabledByTrace) {
            // The premain half of "loudly": the WARN is already out, this is the part the wire
            // and the startup summary read.
            Health.tier1bDisabledByTrace(t.scope.includeDescription());
        }

        // ---- 5. redaction + projection ----
        Redaction.allow(t.redactAllow);
        final Projection projection = Projection.load(t.projectionPath);
        Observations.install(projection);

        // ---- 6. the output path, before anything can produce output ----
        final TraceSender s = new TraceSender(t);
        traceSender = s;
        final TraceDocument doc = new TraceDocument(t.collapsePassThroughs, t.recordTimings,
                t.artifact.isEmpty() ? o.artifactOverride : t.artifact,
                t.instanceId, System.getProperty("ax.build.sha", o.buildShaOverride));
        final TraceDispatcher d = new TraceDispatcher(t, doc, s);
        traceDispatcher = d;
        d.start();

        // ---- 7. the runtime, then the gate. Order matters: TraceGate.install publishes ARMED
        //         last, and nothing may be able to start a trace before the runtime can hold one.
        TraceRuntime.install(t.maxConcurrent, t.propagateExecutors, t.parallelStreamWindow, true);
        TraceGate.install(t.headerName, t.token, new RateCap(t.ratePerMinute), d, t.recordPath,
                t.maxFrames, t.maxDepth, t.maxObsPerFrame, t.maxArmsPerFrame, true);

        traceActive = true;
        TLog.info("trace tier ARMED: " + t.summary()
                + " projection=" + projection.summary()
                + " redactAllow=" + t.redactAllow.size()
                + " ownRuntime=" + IgnoreRules.traceRuntime().replace('/', '.')
                + " | " + c.summary());
        // THE TRADE, in the startup line, every time. An operator must not have to read a
        // markdown file to learn that a property they were promised no longer holds.
        TLog.info("TRADE-OFF, stated at startup: trace probes are NOT strippable, so for "
                + t.scope.includeDescription() + " the \"steady state reaches zero overhead\" "
                + "property of Tier-1b does not apply while this tier is armed. "
                + (c.tier1bDisabledByTrace
                        ? "Tier-1b auto-strip is therefore DISABLED for that intersection and for "
                          + "nothing else; classes covered by ax.include.packages but outside the "
                          + "traced scope still strip to zero. "
                        : "")
                + "Untraced requests still pay only one static load and one branch per probe "
                + "site (measured; see modules/ax-agent/docs/TRADE-OFFS.md). Master switch: "
                + "ax.trace.enabled=false.");
        return true;
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

    // ---------------- trace tier ops / test hooks (SCOPE-v3.1) ----------------
    //
    // Prefixed `trace`, because `active()` and `armed()` already mean something here. These are
    // the hooks the migrated trace smoke suite calls reflectively; they used to live on
    // io.auxin.trace.TraceAgent, which no longer exists.

    /** Did the trace tier arm in this JVM? */
    public static boolean traceActive() { return traceActive; }

    /** The trace tier's configuration, never null. */
    public static TraceOptions traceOptions() { return options().trace; }

    /** The Tier-1b overlap decision. Never null; inert when the trace tier is off. */
    public static StripConflict traceStripConflict() { return stripConflict; }

    /**
     * Is Tier-1b's auto-strip suppressed for the traced scope on this JVM? The one field that
     * explains a non-zero steady-state overhead, and the reason it is not a silent trade.
     */
    public static boolean tier1bDisabledByTrace() { return Health.tier1bDisabledByTrace(); }

    /** Distinct classes Tier-1b declined to strip because they are traced. */
    public static long tier1bTraceBlockedClasses() { return Health.tier1bTraceBlockedClasses(); }

    /** Is trace activation armed in this JVM? */
    public static boolean traceArmed() { return TraceGate.armed(); }

    /** Traces in flight. Must return to 0 after every request, including the ones that threw. */
    public static int traceActiveTraces() { return TraceRuntime.activeTraces(); }

    public static int traceFramesRegistered() { return SiteRegistry.frameCount(); }

    public static int traceArmsRegistered() { return SiteRegistry.armCount(); }

    public static int traceObservationsRegistered() { return SiteRegistry.obsCount(); }

    /** The last trace document built, whether or not the collector accepted it. */
    public static String traceLastDocument() {
        TraceSender s = traceSender;
        return s == null ? null : s.lastBody();
    }

    /** Blocks until every queued trace document has been built, or the deadline passes. */
    public static boolean traceDrain(long timeoutMs) {
        TraceDispatcher d = traceDispatcher;
        return d == null || d.drain(timeoutMs);
    }

    public static long traceDocumentsBuilt() {
        TraceDispatcher d = traceDispatcher;
        return d == null ? 0 : d.documentsBuilt();
    }

    /** Latches the trace tier off down the same path an unexpected Throwable takes. Break-glass. */
    public static void traceForceFailOpen(String why) { TraceRuntime.forceFailOpen(why); }

    private AuxinAgent() { throw new AssertionError(); }
}
