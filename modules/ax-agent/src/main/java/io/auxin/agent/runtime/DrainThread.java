package io.auxin.agent.runtime;

import io.auxin.agent.config.EnvironmentClassification;
import io.auxin.agent.config.Options;
import io.auxin.agent.health.Health;
import io.auxin.agent.instrument.ProbeStripper;
import io.auxin.agent.manifest.Manifest;
import io.auxin.agent.transport.Batch;
import io.auxin.agent.transport.HttpSender;
import io.auxin.agent.transport.WindowPayload;
import io.auxin.agent.util.Log;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single background thread. It owns every aggregate in the agent.
 *
 * <p>Four jobs, in order, on a fixed interval:
 * <ol>
 *   <li>drain the MPSC rings — tier-2's and the call-edge tier's — and fold their events into
 *       the (single-writer, non-atomic) aggregators</li>
 *   <li>every {@code ax.flush.interval.ms}, serialise a window and POST it</li>
 *   <li>retransform classes whose INSTALLED probes are all set, removing their probes
 *       (Tier-1b — see {@link #strippable})</li>
 *   <li>reap a leaked edge-trace gate, if there is one</li>
 * </ol>
 *
 * <p><b>No shutdown hook</b>, ever (C17): Kubernetes SIGKILL and OOMKill run none, so anything
 * that only happens at exit is data that does not exist. Interval flush only.
 */
public final class DrainThread implements Runnable {

    private static final int DRAIN_LIMIT = 65536;

    /**
     * Drain cycles the edge gate may stay non-zero with no new edge event before it is treated
     * as a leaked trace. 25 cycles is 5s at the default 200ms interval. A root invocation that
     * legitimately runs longer than that without recording a new edge (a batch job that already
     * hit its per-root cap) is indistinguishable from a thread that died between
     * {@code rootEnter} and {@code rootExit}, so it is counted rather than assumed away.
     */
    private static final int EDGE_REAP_CYCLES = 25;

    /**
     * {@code ax.edges.sample.rate=auto} aims for this many sampled root invocations per flush
     * window (BUG #26).
     *
     * <p>Twenty, because that is the smallest number that is obviously not zero. The tier's
     * output is a call GRAPH, not a rate: the shape of a request's call tree is the same on the
     * first sampled trace as on the thousandth, so a handful of traces per window already draws
     * it, and every extra one is cost for a picture you already have. A development-volume
     * service gets a graph instead of an empty array; a 10k-rps pod converges to a divisor near
     * its own throughput instead of paying 10 traces a second for the same edges.
     */
    private static final int EDGES_AUTO_TARGET_SAMPLES = 20;

    /** Bounds for the auto divisor. Never 0 (that is not a divisor) and never past 16 bits. */
    private static final int EDGES_AUTO_MIN_RATE = 1;
    private static final int EDGES_AUTO_MAX_RATE = 65536;

    private final Options options;
    private final Manifest manifest;
    private final Ring ring;
    private final Ring edgeRing;
    private final EdgeAggregator edgeAggregator;
    private final Tier2Aggregator aggregator;
    private final HttpSender sender;
    private final ProbeStripper stripper;

    /**
     * The Tier-1b / trace overlap decision, taken once at premain. Consulted per class by
     * {@link #strippable}; inert (and free) when the trace tier is off, which is the default.
     */
    private final io.auxin.trace.config.StripConflict stripConflict;
    private final Instrumentation instrumentation;
    private final Clock clock;
    private final EnvironmentClassification environment;
    private final CoverageSnapshot snapshot = new CoverageSnapshot();

    /**
     * The window-local exception-class name table (CONTRACTS section 2 v4, BUG #24). One
     * instance, reset per window, drain thread only.
     */
    private final ErrorClassTable errorClasses = new ErrorClassTable();

    /**
     * Tier-2 calls folded into the window that was just built: the free evidence that boundary
     * methods really were entered. Used for {@code edgesStarvedOfSamples} and as the root-count
     * estimate for {@code ax.edges.sample.rate=auto}. Drain thread only.
     */
    private long windowBoundaryCalls;

    /** {@code edgesSampledRoots} at the previous flush, for the per-window delta. */
    private long lastSampledRoots;

    /**
     * Classes we believe are de-instrumented. <b>Revocable</b> (G5 section 7): any third party's
     * {@code retransformClasses} makes the JVM replay {@code ProbeInstaller}'s cached load-time
     * output, which silently reinstalls every probe we removed. This used to be a permanent
     * decision, so the probes came back, stayed, and {@code classesStripped} went on claiming
     * they were gone. Correctness was never affected — a reinstalled probe only ever sets a bit
     * that is already set — but the zero-overhead claim and the metric were both wrong.
     *
     * <p>Concurrent because {@link #stripNow} is an ops hook called from arbitrary threads while
     * the drain thread is in {@link #stripCoveredClasses}.
     */
    private final java.util.Set<String> stripped =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * Strip attempts per class, over the life of the JVM. Bounded: an agent that retransforms in
     * a loop against another agent that keeps reinstating our probes is a retransform storm, and
     * a storm is the A11 startup-CPU failure mode arriving late. After
     * {@link #MAX_STRIP_ATTEMPTS} we leave the probes in place and say so.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> stripAttempts =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /**
     * Classes de-instrumented by the {@link #stripNow} break-glass hook rather than by coverage.
     * They are not fully covered, so {@link #stripCoveredClasses} would never pick them up
     * again — but an explicit operator request must not be silently undone either.
     */
    private final java.util.Set<String> forcedStrips =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static final int MAX_STRIP_ATTEMPTS = 3;

    private volatile Thread thread;
    private volatile boolean running = true;
    private volatile String lastPayload = "";
    private long windowStartMs = System.currentTimeMillis();

    /** Consecutive drain cycles with the edge gate up and no new edge event. Drain thread only. */
    private int edgeIdleCycles;
    private long lastEdgeEvents;

    public DrainThread(Options options, Manifest manifest, Ring ring, Tier2Aggregator aggregator,
                       Ring edgeRing, EdgeAggregator edgeAggregator,
                       HttpSender sender, ProbeStripper stripper,
                       io.auxin.trace.config.StripConflict stripConflict,
                       Instrumentation instrumentation,
                       Clock clock, EnvironmentClassification environment) {
        this.options = options;
        this.manifest = manifest;
        this.ring = ring;
        this.aggregator = aggregator;
        this.edgeRing = edgeRing;
        this.edgeAggregator = edgeAggregator;
        this.sender = sender;
        this.stripper = stripper;
        this.stripConflict = stripConflict == null
                ? io.auxin.trace.config.StripConflict.inert() : stripConflict;
        this.instrumentation = instrumentation;
        this.clock = clock;
        this.environment = environment;
    }

    public void start() {
        Thread t = new Thread(this, "auxin-drain");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    @Override
    public void run() {
        long lastFlush = System.currentTimeMillis();
        while (running) {
            try {
                Thread.sleep(options.drainIntervalMs);
            } catch (InterruptedException e) {
                if (!running) return;
            }
            try {
                if (ring != null) ring.drain(aggregator, DRAIN_LIMIT);
                if (edgeRing != null) edgeRing.drain(edgeAggregator, DRAIN_LIMIT);
                long now = System.currentTimeMillis();
                if (now - lastFlush >= options.flushIntervalMs) {
                    flush();
                    lastFlush = now;
                }
                if (options.stripEnabled) stripCoveredClasses();
                reapStaleEdgeTraces();
            } catch (Throwable t) {
                // the drain thread must outlive every individual failure
                Log.debug("drain cycle failed", t);
            }
        }
    }

    /** Builds, serialises and sends one window. @return the JSON that was built. */
    public synchronized String flush() {
        if (ring != null) ring.drain(aggregator, DRAIN_LIMIT);
        if (edgeRing != null) edgeRing.drain(edgeAggregator, DRAIN_LIMIT);
        WindowPayload w = buildWindow();
        String json = Batch.toJson(w);
        lastPayload = json;
        if (sender.enabled()) sender.send(json);
        aggregator.resetWindow();
        if (edgeAggregator != null) edgeAggregator.resetWindow();
        windowStartMs = w.windowEndMs;
        // AFTER the window has been serialised, never before: agentHealth.edgesSampleRate must
        // describe the rate the counts in THIS window were produced under (CONTRACTS section 2).
        retuneEdgeSampleRate(w);
        return json;
    }

    public String lastPayload() { return lastPayload; }

    private WindowPayload buildWindow() {
        WindowPayload w = new WindowPayload();
        w.schemaVersion = WindowPayload.WIRE_SCHEMA_VERSION;
        w.buildSha = options.buildShaOverride.isEmpty() ? manifest.buildSha() : options.buildShaOverride;
        w.artifact = options.artifactOverride.isEmpty() ? manifest.artifact() : options.artifactOverride;
        w.instanceId = options.instanceId;
        w.windowStartMs = windowStartMs;
        w.windowEndMs = System.currentTimeMillis();

        w.transformFailures = Health.transformFailures();
        w.classesSkipped = Health.skipped();
        // Health.RING_DROPPED is incremented by the producer for every rejected offer; the
        // ring's own droppedFull/droppedContended are the same events broken down by reason.
        // Adding them would double count, so the wire carries the producer's count only.
        w.ringDropped = Health.ringDropped();
        if (ring != null && Log.debugEnabled() && w.ringDropped > 0) {
            Log.debug("ring drops: full=" + ring.droppedFull()
                    + " contended=" + ring.droppedContended() + " total=" + w.ringDropped);
        }
        w.clockNs = clock.nanosPerCall();
        w.clockDegraded = clock.degraded();
        // BUG #25. clockDegraded says the clock is slow; this says what that costs, in the
        // vocabulary CONTRACTS section 2 pins (full|sampled|disabled). Derived from the clock
        // and not from ax.tier2.enabled: "what the clock allows" and "is the tier armed" are
        // different facts, and the second one is already visible as an empty tier2[].
        w.tier2TimingMode = clock.wireMode();
        w.degraded = Health.degraded();
        w.degradedReason = Health.degradedReason();
        w.classesInstrumented = Health.classesInstrumented();
        w.classesStripped = Health.classesStripped();

        // Call-edge tier (SCOPE-v3). Cumulative counters, exactly like ringDropped and
        // transformFailures; the per-window graph is the edges[] array below.
        w.edgesEnabled = options.edgesEnabled;
        // The rate actually in force, which is not the configured one once `auto` has retuned
        // it. When the tier is off nothing installed a mask, so the configured value is the
        // honest answer there.
        w.edgesSampleRate = options.edgesEnabled
                ? EdgeRuntime.sampleRate() : options.edgesSampleRate;
        w.edgesSampledRoots = Health.edgeRootsSampled();
        w.edgesDropped = Health.edgeRingDropped();
        w.edgesTruncatedDepth = Health.edgesTruncatedDepth();
        w.edgesTruncatedRoot = Health.edgesTruncatedRoot();
        w.edgeTierFailures = Health.edgeTierFailures();
        w.edgeTracesReaped = Health.edgeTracesReaped();
        if (edgeAggregator != null) {
            w.edgesRecorded = edgeAggregator.eventsProcessed();
            w.edgesTruncatedDistinct = edgeAggregator.distinctRefused();
        }
        w.stripFailures = Health.stripFailures();
        w.stripBlocked = Health.stripsBlocked();
        w.stripReArms = Health.stripReArms();
        w.stripMaskMissing = Health.stripMasksMissing();

        // SCOPE-v3.1. The whole reason this is on the wire: classesStripped is a true counter
        // that would otherwise support a false conclusion. These two fields are what let a
        // server or a UI render "steady-state overhead is not zero on this JVM, and here is
        // exactly which scope bought that".
        w.tier1bDisabledByTrace = Health.tier1bDisabledByTrace();
        w.tier1bTraceScope = Health.tier1bTraceScope();
        w.tier1bTraceBlockedClasses = Health.tier1bTraceBlockedClasses();

        // "I was configured to do work and did none" must never look like a clean run
        // (G5-BUG-1). classesInstrumented is cumulative for the JVM, so this latches itself off
        // as soon as one class is instrumented.
        w.scopeMatchedNothing = !options.scope.empty() && w.classesInstrumented == 0;
        if (w.scopeMatchedNothing && Health.warnOnce("scopeMatchedNothing")) {
            Log.warn("ax.include.packages=" + options.scope.includeDescription() + " matched NO "
                    + "instrumented class in this JVM. Zero coverage is being reported and it is "
                    + "NOT evidence that the application is dead. Check "
                    + "ax_classes_skipped_total (this window: " + w.classesSkipped + "), the "
                    + "package spelling, and whether the build manifest is the one for this "
                    + "artifact. Reported on the wire as scopeMatchedNothing=true.");
        }

        // C50 — fail closed. Unclassified or test-runner JVMs report, but never as evidence.
        w.environment = environment.environment();
        w.livenessEvidence = environment.livenessEvidence();
        w.testRunnerDetected = environment.testRunnerDetected();

        // G5-FINDING-4. classesLoaded keeps its CONTRACTS section 2 name and meaning, but is now
        // sourced from what the transformer SAW rather than from what it managed to instrument,
        // so C10 can distinguish "never loaded" from "skipped". The instrumented set ships
        // alongside it, additively, instead of masquerading as it.
        w.classesLoaded = ProbeHolder.loadedClassNames();
        w.instrumentedClasses = ProbeHolder.instrumentedClasses();
        w.classesLoadedTruncated = ProbeHolder.loadedTruncated();
        if (w.classesLoadedTruncated && Health.warnOnce("classesLoadedTruncated")) {
            Log.warn("classesLoaded hit its cap of " + w.classesLoaded.size() + " classes and is "
                    + "no longer complete; absence from it is no longer evidence that a class "
                    + "was never loaded. Reported as classesLoadedTruncated=true.");
        }

        for (Map.Entry<String, boolean[]> e : ProbeHolder.arrays().entrySet()) {
            String cls = e.getKey();
            boolean[] probes = e.getValue();
            snapshot.changedSince(cls, probes);
            WindowPayload.Coverage c = new WindowPayload.Coverage();
            c.className = cls;
            Manifest.ClassEntry entry = manifest.byInternalName(cls.replace('.', '/'));
            c.schemaHash = entry == null ? "" : entry.schemaHash;
            c.probesBase64 = CoverageSnapshot.toBase64(probes);
            // C51 requires a not-dynamically-observable method to stay DISTINGUISHABLE from a
            // de-instrumented one, and neither may ever read as "never ran". The probe bitset
            // alone cannot express that: a slot with no probe is never written, so its zero is
            // silence, not an observation. Ship the mask next to it.
            //
            // When the mask is unknown (structurally impossible, counted as stripMaskMissing)
            // an ALL-ZERO mask is the only safe answer: it says "no index in this class may be
            // read as evidence of death", which loses a dead-code candidate. Claiming the
            // opposite would invent up to probeCount false dead methods.
            boolean[] installed = ProbeHolder.installedProbes(cls);
            c.probesInstalledBase64 = CoverageSnapshot.toBase64(
                    installed != null && installed.length == probes.length
                            ? installed : new boolean[probes.length]);
            w.coverage.add(c);
        }

        // One name table for the whole window (CONTRACTS section 2 v4). Reset here, filled by
        // the records below, and only ids those records actually reference end up in it.
        errorClasses.reset();
        long boundaryCalls = 0;
        for (Map.Entry<Integer, Tier2Aggregator.MethodStats> e : aggregator.stats().entrySet()) {
            Tier2Aggregator.MethodStats s = e.getValue();
            // Counted before any `continue`: this is the evidence that boundary methods were
            // entered at all, and it must not depend on the record surviving to the wire.
            boundaryCalls += s.calls;
            if (s.empty()) continue;
            Tier2Registry.Entry reg = Tier2Registry.get(e.getKey().intValue());
            if (reg == null) continue;
            WindowPayload.Tier2 t = new WindowPayload.Tier2();
            t.className = reg.className;
            t.idx = reg.idx;
            t.calls = s.calls;
            t.errors = s.errors;
            t.buckets = s.histogram.trimmed();
            if (s.errorCounts != null) {
                for (int id = 1; id < s.errorCounts.length; id++) {
                    final long n = s.errorCounts[id];
                    if (n == 0) continue;
                    t.errorTypes.put(ErrorIds.name(id), Long.valueOf(n));
                    // BUG #24: the same counts, keyed by a window-local id the reader can
                    // resolve through errorClasses. ADDED, not put: several global ids fold
                    // into the 255 overflow bucket and the last one must not erase the rest.
                    final Integer local = Integer.valueOf(errorClasses.localIdFor(id));
                    final Long had = t.errorsByClass.get(local);
                    t.errorsByClass.put(local,
                            Long.valueOf(had == null ? n : had.longValue() + n));
                }
            }
            w.tier2.add(t);
        }
        // Copied, not aliased: `errorClasses` is reset at the top of the next window and this
        // payload has to stay valid for the sender and for lastPayload().
        w.errorClasses = new java.util.LinkedHashMap<Integer, String>(errorClasses.names());
        windowBoundaryCalls = boundaryCalls;

        // BUG #26. Armed, roots entered, nothing sampled => the empty edges[] is a fact about
        // the RATE. edgesSampledRoots is cumulative for the JVM, so this latches itself off for
        // good the moment one root is ever sampled, and boundaryCalls is this window's evidence
        // that there was something to sample. With tier-2 off there is no such evidence and the
        // flag stays false: a missed warning is recoverable, a false one destroys trust in it.
        w.edgesStarvedOfSamples =
                options.edgesEnabled && w.edgesSampledRoots == 0 && boundaryCalls > 0;
        if (w.edgesStarvedOfSamples && Health.warnOnce("edgesStarvedOfSamples")) {
            Log.warn("the call-edge tier is ARMED but has not sampled one root invocation: "
                    + "ax.edges.sample.rate=" + w.edgesSampleRate + " traces 1 root entry in "
                    + w.edgesSampleRate + " and this window saw only " + boundaryCalls
                    + " boundary-method call(s). edges[] is empty because of the RATE, not "
                    + "because nothing ran, and tierArmed/edgesEnabled=true is telling you the "
                    + "truth. At development volume set ax.edges.sample.rate=64, or "
                    + "ax.edges.sample.rate=auto to target ~" + EDGES_AUTO_TARGET_SAMPLES
                    + " sampled roots per flush window. Reported on the wire as "
                    + "agentHealth.edgesStarvedOfSamples=true.");
        }

        if (edgeAggregator != null) {
            final WindowPayload target = w;
            edgeAggregator.forEach(new EdgeAggregator.EdgeVisitor() {
                @Override
                public void edge(int callerEdgeId, int calleeEdgeId, long count) {
                    String from = EdgeRegistry.className(callerEdgeId);
                    String to = EdgeRegistry.className(calleeEdgeId);
                    // An unknown id cannot happen (ids are assigned before the bytecode that
                    // pushes them exists) but a half-registered edge must never become an edge
                    // between nulls on the wire.
                    if (from == null || to == null) return;
                    WindowPayload.Edge e = new WindowPayload.Edge();
                    e.fromClass = from;
                    e.fromIdx = EdgeRegistry.idx(callerEdgeId);
                    e.toClass = to;
                    e.toIdx = EdgeRegistry.idx(calleeEdgeId);
                    e.count = count;
                    target.edges.add(e);
                }
            });
        }
        return w;
    }

    /**
     * {@code ax.edges.sample.rate=auto}: aim for {@link #EDGES_AUTO_TARGET_SAMPLES} sampled root
     * invocations per flush window instead of a fixed divisor (BUG #26). Drain thread only,
     * called once per flush, after the window has been serialised.
     *
     * <h3>The arithmetic</h3>
     * <pre>
     *   rootEntries  = the window's tier-2 call count            (EXACT, and free)
     *                = sampledRootsDelta * rateInForce           (fallback, tier-2 off)
     *   targetRate   = rootEntries / EDGES_AUTO_TARGET_SAMPLES   (integer division)
     *   newRate      = largest power of two <= targetRate, clamped to [1, 65536]
     * </pre>
     * Worked: 4096 root entries in the window, target 20 -&gt; 4096/20 = 204 -&gt; 128. The next
     * window samples 4096/128 = 32 roots. 10 entries -&gt; 10/20 = 0 -&gt; clamped to 1: trace
     * every root, which is the most 10 entries can yield and still under target. 2,000,000
     * entries -&gt; 100,000 -&gt; clamped to 65536.
     *
     * <p><b>Why the tier-2 call count and not the sample count.</b> It makes this a measurement
     * rather than a control loop: the estimate does not depend on the divisor it is about to
     * set, so the rate lands on its final value in ONE window and cannot oscillate. (The
     * fallback, used only with {@code ax.tier2.enabled=false}, does feed back — it converges
     * geometrically and is the reason the bounds are enforced on every step and not once.)
     *
     * <p><b>Rounded DOWN to a power of two</b>, both because the sampling decision is a bitmask
     * ({@code (++n & (N-1)) == 0}) and because down is the safe direction: the bug being fixed
     * is a tier that recorded nothing, so where two divisors bracket the target the one that
     * samples more is the one to take.
     *
     * <p>A window with no evidence at all (no tier-2 calls and no samples — an out-of-band
     * {@code flushNow()} on an idle JVM) leaves the rate exactly as it was. Retuning to 1 on
     * the strength of an empty window would arm every root entry in the next one.
     */
    private void retuneEdgeSampleRate(WindowPayload w) {
        if (!options.edgesSampleRateAuto || !options.edgesEnabled) return;
        final long sampledDelta = w.edgesSampledRoots - lastSampledRoots;
        lastSampledRoots = w.edgesSampledRoots;
        final int rateInForce = w.edgesSampleRate < 1 ? 1 : w.edgesSampleRate;
        final long rootEntries = windowBoundaryCalls > 0
                ? windowBoundaryCalls
                : sampledDelta * (long) rateInForce;
        if (rootEntries <= 0) return;

        long target = rootEntries / EDGES_AUTO_TARGET_SAMPLES;
        if (target > EDGES_AUTO_MAX_RATE) target = EDGES_AUTO_MAX_RATE;
        int rate = target < EDGES_AUTO_MIN_RATE
                ? EDGES_AUTO_MIN_RATE
                : Integer.highestOneBit((int) target);
        if (rate < EDGES_AUTO_MIN_RATE) rate = EDGES_AUTO_MIN_RATE;
        if (rate > EDGES_AUTO_MAX_RATE) rate = EDGES_AUTO_MAX_RATE;
        if (rate == rateInForce) return;

        EdgeRuntime.setSampleRate(rate);
        final String why = "ax.edges.sample.rate=auto retuned 1-in-" + rateInForce + " -> 1-in-"
                + rate + " (" + rootEntries + " root entries in the last window, targeting ~"
                + EDGES_AUTO_TARGET_SAMPLES + " sampled roots per window). Every window reports "
                + "the rate its own counts were produced under as agentHealth.edgesSampleRate.";
        if (Health.warnOnce("edgesAutoRate")) Log.info(why);
        else Log.debug(why);
    }

    /**
     * The edge tier's safety valve. {@code EdgeRuntime}'s gate is a count of in-flight sampled
     * traces, and a trace that is never closed leaves it non-zero for ever — at which point
     * every instrumented call in the JVM pays a thread-local read instead of a load and a
     * branch. The root's {@code catch (Throwable)} handler is what makes that impossible in
     * normal operation; this is what makes it recoverable when something abnormal happens
     * (a thread killed outright between entry and exit).
     *
     * <p>Resetting the gate can at worst truncate one in-flight trace, and it is counted, so the
     * wire can never say "no edges" without also saying why.
     */
    private void reapStaleEdgeTraces() {
        if (edgeRing == null || edgeAggregator == null) return;
        if (EdgeRuntime.activeTraces() == 0) {
            edgeIdleCycles = 0;
            return;
        }
        final long events = edgeAggregator.eventsProcessed();
        if (events != lastEdgeEvents) {
            lastEdgeEvents = events;
            edgeIdleCycles = 0;
            return;
        }
        if (++edgeIdleCycles < EDGE_REAP_CYCLES) return;
        edgeIdleCycles = 0;
        if (!EdgeRuntime.reapStaleTraces()) return;
        Health.edgeTraceReaped();
        if (Health.warnOnce("edgeTraceReaped")) {
            Log.warn("an edge trace was open for " + (EDGE_REAP_CYCLES * options.drainIntervalMs)
                    + "ms with no new edge recorded, so its gate has been reset. Either a thread "
                    + "died between a boundary method's entry and its exit, or a boundary method "
                    + "really does run that long after hitting ax.edges.max.per.root. Counted as "
                    + "agentHealth.edgeTracesReaped; coverage and tier-2 are unaffected.");
        }
    }

    /**
     * Tier-1b. Rate limited, because a retransform storm is exactly the startup-CPU failure mode
     * A11 documents — the cure must not become the disease.
     */
    private void stripCoveredClasses() {
        if (instrumentation == null) return;
        reArmReappearedProbes();

        List<Class<?>> batch = new ArrayList<Class<?>>();
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, boolean[]> e : ProbeHolder.arrays().entrySet()) {
            if (batch.size() >= options.stripMaxPerCycle) break;
            String cls = e.getKey();
            if (stripped.contains(cls)) continue;
            if (attempts(cls) >= MAX_STRIP_ATTEMPTS) continue;   // strip-blocked, bounded
            if (!strippable(cls, e.getValue())) continue;
            Class<?> c = ProbeHolder.loadedClass(cls);
            if (c == null) continue;    // pre-55 field fallback: no strip handle, never stripped
            batch.add(c);
            names.add(cls);
        }
        if (batch.isEmpty()) return;

        for (int i = 0; i < names.size(); i++) {
            stripAttempts.put(names.get(i), Integer.valueOf(attempts(names.get(i)) + 1));
            stripper.arm(names.get(i).replace('.', '/'));
        }
        try {
            instrumentation.retransformClasses(batch.toArray(new Class<?>[0]));
            int real = 0;
            for (int i = 0; i < names.size(); i++) {
                if (recordStripOutcome(names.get(i))) real++;
            }
            Log.debug("de-instrumented " + real + " of " + names.size()
                    + " class(es) whose installed probes were all set");
        } catch (Throwable t) {
            Health.stripFailure();
            Log.debug("retransform (strip) failed", t);
        } finally {
            for (int i = 0; i < names.size(); i++) {
                stripper.disarm(names.get(i).replace('.', '/'));
            }
        }
    }

    /**
     * Is this class a Tier-1b candidate on this cycle?
     *
     * <p><b>The gate is the installed probes, not the whole array.</b> The array is sized to
     * every probe-eligible method in the build manifest, but {@code ProbeEmitter} installs a
     * probe at only some of those indices — C51 exempts a method that cannot be covered
     * dynamically (a single-instruction body, a plain field accessor, a delegating constructor),
     * an entry frame that cannot be built safely skips one method, and
     * {@code ax.tier1.enabled=false} skips all of them. Nothing ever writes a slot with no probe
     * in it, so the old {@code allSet(array)} gate was <b>unsatisfiable for the life of the
     * JVM</b> for any class carrying one such method: it stayed a non-candidate for ever and its
     * probes stayed on the hot path, which is precisely the cost this tier exists to remove. On
     * the G5 demo that was 13 of 20 probed classes.
     *
     * <p>Three answers, in order:
     * <ol>
     *   <li><b>A forced strip re-arms unconditionally.</b> {@link #stripNow} only records a class
     *       here after probes were genuinely removed from it, so it is strippable by
     *       construction; an explicit operator request must not be silently dropped because the
     *       class was never fully covered.</li>
     *   <li><b>No usable mask: fail open, count, log once.</b> Structurally impossible (the mask
     *       is written before the array, from the same manifest {@code probeCount}), so it is
     *       reported rather than assumed away. Keeping the probes costs CPU; guessing costs
     *       either a phantom strip or a permanently-installed probe.</li>
     *   <li>Otherwise every INSTALLED probe must be set. A class with <b>no</b> installed probe
     *       has nothing to strip and is excluded here rather than counted as a successful strip —
     *       {@link CoverageSnapshot#allInstalledSet} returns false for an empty installed set for
     *       exactly that reason.</li>
     * </ol>
     */
    private boolean strippable(String cls, boolean[] live) {
        try {
            // SCOPE-v3.1, AND IT COMES FIRST. A class in BOTH the traced and the instrumented
            // scope can never reach zero steady-state overhead -- its trace probes are not
            // strippable -- so Tier-1b must not strip it and report a number that reads as
            // "overhead is now zero". Refused per class, counted per class, and announced once
            // at premain; a class outside ax.trace.include.packages falls straight through and
            // strips exactly as it did before this tier existed.
            //
            // Ahead of the forcedStrips check on purpose. stripNow() carries the same refusal
            // (see below): a break-glass hook that reported a successful strip for a traced
            // class would recreate the exact failure this gate exists to prevent -- a true
            // counter and a false conclusion -- one manual call at a time.
            if (stripConflict.blocksStrip(cls)) {
                Health.tier1bStripBlocked(cls);
                return false;
            }
            if (forcedStrips.contains(cls)) return true;
            final boolean[] installed = ProbeHolder.installedProbes(cls);
            if (installed == null || live == null || installed.length != live.length) {
                undecidable(cls, "mask="
                        + (installed == null ? "absent" : "length " + installed.length)
                        + ", probes=" + (live == null ? "absent" : "length " + live.length));
                return false;
            }
            return CoverageSnapshot.allInstalledSet(live, installed);
        } catch (Throwable t) {
            // Nothing here can throw, which is exactly why an escape must not be swallowed:
            // the whole class of bug being fixed is a Tier-1b decision that went wrong in
            // silence. Same outcome as a missing mask — keep the probes, count, say so once.
            undecidable(cls, String.valueOf(t));
            return false;
        }
    }

    /** Tier-1b declined to decide about one class. Fail open: keep the probes, count, warn once. */
    private void undecidable(String cls, String why) {
        Health.stripMaskMissing();
        if (Health.warnOnce("stripMaskMissing:" + cls)) {
            Log.warn("Tier-1b cannot tell which of " + cls + "'s probe slots actually carry a "
                    + "probe (" + why + "), so the class keeps its probes and its steady-state "
                    + "cost. Coverage and correctness are unaffected. Counted as "
                    + "agentHealth.stripMaskMissing.");
        }
    }

    /**
     * Grades ONE strip, from what the stripper actually did rather than from the absence of an
     * exception (G5-BUG-3).
     *
     * <p>{@code retransformClasses} returning normally proves only that the JVM accepted the
     * bytes — and when the stripper matches nothing it hands back {@code null}, which the JVM
     * accepts happily. That was reported as success: {@code classesStripped} went up,
     * {@code stripFailures} stayed at zero, and the class was marked stripped for ever, so it
     * was never retried even though every probe was still firing.
     *
     * @return true when probes were genuinely removed.
     */
    private boolean recordStripOutcome(String cls) {
        final String internal = cls.replace('.', '/');
        final int removed = stripper.removedBy(internal);
        if (removed > 0) {
            stripped.add(cls);
            stripper.markStripped(internal);
            Health.classStripped();
            return true;
        }
        // Nothing was removed. NOT a success: no classesStripped, and not marked stripped, so
        // the class stays a candidate until the bounded attempt count runs out.
        final int blocked = stripper.blockedIn(internal);
        final int used = attempts(cls);
        Health.stripBlocked();
        if (Health.warnOnce("stripBlocked:" + cls)) {
            Log.warn("Tier-1b removed 0 probes from " + cls + " (" + blocked + " probe(s) still "
                    + "present, attempt " + used + " of " + MAX_STRIP_ATTEMPTS + "). Another "
                    + "transformer has rewritten our probe sequence, so the class keeps its "
                    + "probes and its steady-state cost. Counted as stripBlocked; "
                    + "classesStripped deliberately NOT incremented.");
        }
        if (used >= MAX_STRIP_ATTEMPTS) {
            Log.warn("Tier-1b giving up on " + cls + " after " + used + " attempts; its probes "
                    + "stay installed for the life of this JVM. Coverage is unaffected.");
        }
        return false;
    }

    /**
     * Revokes the {@code stripped} decision for classes whose probes a foreign retransform
     * replayed, and lets them be stripped once more within the attempt budget (G5 section 7).
     */
    private void reArmReappearedProbes() {
        List<String> back = stripper.takeReappeared();
        for (int i = 0; i < back.size(); i++) {
            final String internal = back.get(i);
            final String cls = internal.replace('/', '.');
            if (!stripped.remove(cls)) continue;
            stripper.unmarkStripped(internal);
            Health.stripReArm();
            final int used = attempts(cls);
            if (used >= MAX_STRIP_ATTEMPTS) {
                if (Health.warnOnce("reStripExhausted:" + cls)) {
                    Log.warn("probes on " + cls + " were reinstalled by another agent's "
                            + "retransform and the Tier-1b attempt budget (" + MAX_STRIP_ATTEMPTS
                            + ") is spent; they stay installed. classesStripped no longer counts "
                            + "this class as de-instrumented.");
                }
                continue;
            }
            Log.debug("probes reappeared on " + cls + " after a foreign retransform; re-arming "
                    + "Tier-1b (attempt " + (used + 1) + " of " + MAX_STRIP_ATTEMPTS + ")");
        }
    }

    private int attempts(String cls) {
        Integer n = stripAttempts.get(cls);
        return n == null ? 0 : n.intValue();
    }

    /**
     * Does Tier-1b currently believe this class is de-instrumented? Ops/test hook.
     *
     * <p>Revocable, like the set behind it: a foreign retransform that replays our probes takes
     * a class back out (G5 section 7), so this is the live belief and not a latch.
     */
    public boolean isStripped(String dottedClassName) {
        return dottedClassName != null && stripped.contains(dottedClassName);
    }

    /**
     * Ops/test hook: strip one class immediately, regardless of coverage.
     *
     * @return true only when probes were actually removed. A retransform the JVM accepted but
     *         that removed nothing returns false (G5-BUG-3) — it is exactly the case an operator
     *         reaching for this hook needs to be told about.
     */
    public boolean stripNow(Class<?> c) {
        if (instrumentation == null || c == null) return false;
        // SCOPE-v3.1: the same refusal the automatic path makes, for the same reason. Removing
        // the coverage probes from a traced class does not take its overhead to zero -- the
        // trace probes stay -- so reporting a successful strip would be a lie an operator has
        // no way to see through. Refused, counted, and said once per class.
        if (stripConflict.blocksStrip(c.getName())) {
            Health.tier1bStripBlocked(c.getName());
            if (Health.warnOnce("tier1bTraceBlocked:" + c.getName())) {
                Log.warn("stripNow(" + c.getName() + ") REFUSED: the class is in both "
                        + "ax.include.packages and ax.trace.include.packages, and a trace probe "
                        + "can never be stripped. De-instrumenting it would remove the coverage "
                        + "probes and report a strip, while the hot path kept every trace probe. "
                        + "Counted as agentHealth.tier1bTraceBlockedClasses.");
            }
            return false;
        }
        String internal = c.getName().replace('.', '/');
        stripAttempts.put(c.getName(), Integer.valueOf(attempts(c.getName()) + 1));
        stripper.arm(internal);
        try {
            instrumentation.retransformClasses(new Class<?>[]{c});
            boolean ok = recordStripOutcome(c.getName());
            if (ok) forcedStrips.add(c.getName());
            return ok;
        } catch (Throwable t) {
            Health.stripFailure();
            Log.warn("stripNow failed for " + c.getName() + ": " + t, t);
            return false;
        } finally {
            stripper.disarm(internal);
        }
    }
}
