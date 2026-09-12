package io.auxin.trace;

import io.auxin.trace.config.Projection;
import io.auxin.trace.config.StripConflict;
import io.auxin.trace.config.TraceOptions;
import io.auxin.trace.instrument.EntrySignatures;
import io.auxin.trace.instrument.TraceInstaller;
import io.auxin.trace.instrument.TraceIgnoreRules;
import io.auxin.trace.runtime.Observations;
import io.auxin.trace.runtime.RateCap;
import io.auxin.trace.runtime.Redaction;
import io.auxin.trace.runtime.SiteRegistry;
import io.auxin.trace.runtime.TraceGate;
import io.auxin.trace.runtime.TraceHealth;
import io.auxin.trace.runtime.TraceRuntime;
import io.auxin.trace.transport.TraceDispatcher;
import io.auxin.trace.transport.TraceDocument;
import io.auxin.trace.transport.TraceSender;
import io.auxin.trace.util.TLog;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent entry point for the per-request tracer. <b>Its own {@code -javaagent}</b>, its own
 * switch, its own module — see TRADE-OFFS.md for why that separation is not negotiable.
 *
 * <h3>This tier is not ax-agent's tier 1, 2, 1b or the call-edge tier, and must never be folded
 * into one</h3>
 * auxin's stated invariants are <i>no values, aggregate-only, zero-allocation, strippable</i>.
 * The tracer breaks three of them, and one structurally: <b>a trace probe can never be
 * stripped</b>, because you cannot know in advance which request will be traced. Folding it into
 * tier-1 would make tier-1's headline claim false; folding it into tier-2 would put value capture
 * behind a switch people already have on. So: separate jar, separate premain, separate transformer,
 * separate endpoint, separate document, and a startup line that says what was given up.
 *
 * <h3>Wiring order</h3>
 * <pre>
 *   options -> MASTER SWITCH (default OFF) -> scope (default deny) -> production token check
 *           -> TIER-1b CONFLICT DETECTION  (refuse / disable-strip / allow)
 *           -> projection config + redaction allow-list
 *           -> rate cap -> dispatcher thread -> TraceRuntime.install -> TraceGate.install
 *           -> addTransformer(t)      canRetransform = FALSE
 *           -> conflict VERIFICATION against the live ax-agent
 * </pre>
 * Every step is wrapped: anything that goes wrong means the tracer does nothing and the
 * application starts normally.
 */
public final class TraceAgent {

    private static volatile TraceOptions options;
    private static volatile TraceDispatcher dispatcher;
    private static volatile TraceSender sender;
    private static volatile StripConflict conflict;
    private static volatile boolean active;

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            init(agentArgs, inst);
        } catch (Throwable t) {
            // FAIL OPEN. A JVM that starts without a tracer has lost a debugging tool;
            // a JVM that does not start is an outage.
            TLog.warn("premain failed, the tracer is disabled for this JVM: " + t, t);
        }
    }

    /**
     * Dynamic attach is deliberately NOT supported, and for a sharper reason than ax-agent's:
     * probes install at initial class load only, so attaching to a running JVM would trace
     * nothing while making it look armed — the worst possible combination for a tool whose whole
     * job is to answer "what happened on that request".
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        TLog.warn("dynamic attach is not supported: trace probes install at initial class load "
                + "only, so an attached tracer would report an armed gate and capture nothing. "
                + "Use -javaagent.");
    }

    private static void init(String agentArgs, Instrumentation inst) {
        final long t0 = System.nanoTime();
        TraceOptions o = TraceOptions.parse(agentArgs);
        options = o;

        // ---- 1. the master switch ----
        if (!o.enabled) {
            // One line, at info, and then silence. This is the DEFAULT state, so it must be
            // cheap to say and unmistakable to read.
            TLog.info("per-request tracer is OFF (ax.trace.enabled=false, the default). "
                    + "Nothing is instrumented: no entry probe, no frame probe, no branch probe. "
                    + "This JVM is byte-for-byte what it would be without this agent.");
            return;
        }

        // ---- 2. default deny ----
        if (o.scope.empty() && o.entryScope.empty()) {
            TLog.warn("ax.trace.enabled=true but ax.trace.include.packages is unset: tracing "
                    + "nothing (default-deny). Set ax.trace.include.packages=com.yourcompany. "
                    + "A trace probe records values, so there is no instrument-everything "
                    + "switch in this module and there is not going to be one.");
            return;
        }

        // ---- 3. an unauthenticated trace header in production ----
        if (o.tokenRequiredButMissing()) {
            TLog.warn("REFUSING TO ARM: ax.environment=" + o.environment + " is classified as "
                    + "production and ax.trace.token is unset."
                    + "\n  An unauthenticated " + o.headerName + " header is two things at once:"
                    + "\n    - an AMPLIFICATION vector: one header makes the service do 10-100x "
                    + "the work of the request, and any caller can set it;"
                    + "\n    - a DATA-EXPOSURE vector: any caller can ask for a structural trace "
                    + "of the code path their request took."
                    + "\n  Set ax.trace.token=<a shared secret> (the header value must then equal "
                    + "it), or classify this JVM as non-production if it is not one.");
            return;
        }

        // ---- 4. THE TIER-1b MUTUAL EXCLUSION ----
        StripConflict c = StripConflict.detect(o);
        conflict = c;
        if (!c.armTracer) {
            // StripConflict.detect has already printed the full explanation and the four ways
            // out. Nothing is instrumented.
            return;
        }

        // ---- 5. redaction + projection ----
        Redaction.allow(o.redactAllow);
        Projection projection = Projection.load(o.projectionPath);
        Observations.install(projection);

        // ---- 6. the output path, before anything can produce output ----
        TraceSender s = new TraceSender(o);
        sender = s;
        TraceDocument doc = new TraceDocument(o.collapsePassThroughs, o.recordTimings,
                o.artifact, o.instanceId, System.getProperty("ax.build.sha", ""));
        TraceDispatcher d = new TraceDispatcher(o, doc, s);
        dispatcher = d;
        d.start();

        // ---- 7. the runtime, then the gate. Order matters: TraceGate.install publishes ARMED
        //         last, and nothing may be able to start a trace before the runtime can hold one.
        TraceRuntime.install(o.maxConcurrent, o.propagateExecutors, o.parallelStreamWindow, true);
        RateCap cap = new RateCap(o.ratePerMinute);
        TraceGate.install(o.headerName, o.token, cap, d, o.recordPath, o.maxFrames, o.maxDepth,
                o.maxObsPerFrame, o.maxArmsPerFrame, true);

        // ---- 8. the transformer. canRetransform=false; see TraceInstaller. ----
        EntrySignatures entries = new EntrySignatures(entrySignatureOverrides());
        TraceInstaller installer = new TraceInstaller(o, entries);

        // BUG, found by the smoke suite on its first run:
        //   LinkageError: loader 'app' attempted duplicate class definition for
        //   io.auxin.trace.instrument.TraceIgnoreRules
        // The startup line below touches TraceIgnoreRules. If that first happens AFTER
        // addTransformer, the app loader begins defining the class, the JVM calls our
        // transformer for it, and doTransform's very first statement is
        // TraceIgnoreRules.hardVeto(...) -- a re-entrant load of the class being defined, on
        // the same thread. The hard-veto list would have rejected it, but the veto lives in the
        // class that cannot be loaded yet.
        //
        // ax-agent's AuxinAgent documents exactly this hazard ("resolving IgnoreRules from
        // inside the very first transform is a recursive load of a class the transformer
        // needs") and solves it by touching the class before registering. So: every class on
        // the transform path is resolved HERE, where it can be loaded normally.
        preloadTransformPath(installer);

        inst.addTransformer(installer);

        active = true;
        final long ms = (System.nanoTime() - t0) / 1000000L;
        TLog.info("ARMED in " + ms + "ms: " + o.summary()
                + " entrySignatures=" + entries.count()
                + " projection=" + projection.summary()
                + " redactAllow=" + o.redactAllow.size()
                + " ownRuntime=" + TraceIgnoreRules.ownRuntime()
                + " | " + c.summary());
        // THE TRADE, in the startup line, every time. An operator must not have to read a
        // markdown file to learn that a property they were promised no longer holds.
        TLog.info("TRADE-OFF, stated at startup: trace probes are NOT strippable, so for "
                + o.scope.includeDescription() + " the \"steady state reaches zero overhead\" "
                + "property of ax-agent Tier-1b does not apply while this agent is armed. "
                + "Untraced requests still pay only one static load and one branch per probe "
                + "site (measured; see modules/ax-trace/TRADE-OFFS.md). Master switch: "
                + "ax.trace.enabled=false.");

        // ---- 9. verify the conflict resolution against the LIVE ax-agent ----
        startConflictVerifier(c);
    }

    /**
     * Resolves every class the transformer will touch, before the transformer is registered.
     *
     * <p>A class reached for the first time from inside {@code transform()} is a re-entrant load
     * on the same thread, which the JVM answers with
     * {@code LinkageError: attempted duplicate class definition}. It is not hypothetical: it was
     * the first failure the smoke suite produced, and it presented as
     * "premain failed, the tracer is disabled" <i>after</i> the transformer had already been
     * registered — i.e. a half-armed agent.
     *
     * <p>Running the emitter once over a synthetic class is what actually loads ASM's parser,
     * writer and tree nodes; naming the classes would only load the ones we happen to name.
     */
    private static void preloadTransformPath(TraceInstaller installer) {
        try {
            SiteRegistry.frameCount();
            TraceHealth.skips();
            installer.warmUp(readOwnClassBytes());
        } catch (Throwable t) {
            TLog.debug("preloading the transform path failed; the first transform may be slower",
                    t);
        }
    }

    private static byte[] readOwnClassBytes() {
        try {
            java.io.InputStream in = TraceAgent.class.getClassLoader()
                    .getResourceAsStream("io/auxin/trace/TraceAgent.class");
            if (in == null) return null;
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Runs the verification a few seconds after startup, off the premain path.
     *
     * <p>It cannot run inline: at premain time the other agent may not have run yet, which is the
     * whole ordering question. So it waits, then reads ax-agent's live options reflectively. A
     * daemon thread that sleeps once and exits.
     */
    private static void startConflictVerifier(final StripConflict c) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (!c.verify()) TraceHealth.STRIP_CONFLICT_UNRESOLVED.incrementAndGet();
                } catch (Throwable ignored) {
                    TraceHealth.STRIP_CONFLICT_UNRESOLVED.incrementAndGet();
                }
            }
        }, "ax-trace-conflict-verify");
        t.setDaemon(true);
        t.start();
    }

    private static List<String> entrySignatureOverrides() {
        List<String> out = new ArrayList<String>();
        String v = System.getProperty("ax.trace.entry.signatures");
        if (v == null) v = System.getenv("AX_TRACE_ENTRY_SIGNATURES");
        if (v == null) return out;
        for (String part : v.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    // ---------------- ops / test hooks ----------------

    public static boolean active() { return active; }

    public static TraceOptions options() { return options; }

    public static StripConflict conflict() { return conflict; }

    /** Is activation armed in this JVM? */
    public static boolean armed() { return TraceGate.armed(); }

    /** Traces in flight. Must return to 0 after every request, including the ones that threw. */
    public static int activeTraces() { return TraceRuntime.activeTraces(); }

    public static int framesRegistered() { return SiteRegistry.frameCount(); }

    public static int armsRegistered() { return SiteRegistry.armCount(); }

    public static int observationsRegistered() { return SiteRegistry.obsCount(); }

    /** The last trace document built, whether or not the collector accepted it. */
    public static String lastDocument() {
        TraceSender s = sender;
        return s == null ? null : s.lastBody();
    }

    /** Blocks until every queued document has been built, or the deadline passes. */
    public static boolean drain(long timeoutMs) {
        TraceDispatcher d = dispatcher;
        return d == null || d.drain(timeoutMs);
    }

    public static long documentsBuilt() {
        TraceDispatcher d = dispatcher;
        return d == null ? 0 : d.documentsBuilt();
    }

    /** Latches the tracer off down the same path an unexpected Throwable takes. Break-glass. */
    public static void forceFailOpen(String why) { TraceRuntime.forceFailOpen(why); }

    private TraceAgent() { throw new AssertionError(); }
}
