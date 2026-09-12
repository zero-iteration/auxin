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
 * <p>Three jobs, in order, on a fixed interval:
 * <ol>
 *   <li>drain the MPSC ring and fold events into the (single-writer, non-atomic) aggregator</li>
 *   <li>every {@code ax.flush.interval.ms}, serialise a window and POST it</li>
 *   <li>retransform classes whose probes are all set, removing their probes (Tier-1b)</li>
 * </ol>
 *
 * <p><b>No shutdown hook</b>, ever (C17): Kubernetes SIGKILL and OOMKill run none, so anything
 * that only happens at exit is data that does not exist. Interval flush only.
 */
public final class DrainThread implements Runnable {

    private static final int DRAIN_LIMIT = 65536;

    private final Options options;
    private final Manifest manifest;
    private final Ring ring;
    private final Tier2Aggregator aggregator;
    private final HttpSender sender;
    private final ProbeStripper stripper;
    private final Instrumentation instrumentation;
    private final Clock clock;
    private final EnvironmentClassification environment;
    private final CoverageSnapshot snapshot = new CoverageSnapshot();

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

    public DrainThread(Options options, Manifest manifest, Ring ring, Tier2Aggregator aggregator,
                       HttpSender sender, ProbeStripper stripper, Instrumentation instrumentation,
                       Clock clock, EnvironmentClassification environment) {
        this.options = options;
        this.manifest = manifest;
        this.ring = ring;
        this.aggregator = aggregator;
        this.sender = sender;
        this.stripper = stripper;
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
                long now = System.currentTimeMillis();
                if (now - lastFlush >= options.flushIntervalMs) {
                    flush();
                    lastFlush = now;
                }
                if (options.stripEnabled) stripCoveredClasses();
            } catch (Throwable t) {
                // the drain thread must outlive every individual failure
                Log.debug("drain cycle failed", t);
            }
        }
    }

    /** Builds, serialises and sends one window. @return the JSON that was built. */
    public synchronized String flush() {
        if (ring != null) ring.drain(aggregator, DRAIN_LIMIT);
        WindowPayload w = buildWindow();
        String json = Batch.toJson(w);
        lastPayload = json;
        if (sender.enabled()) sender.send(json);
        aggregator.resetWindow();
        windowStartMs = w.windowEndMs;
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
        w.degraded = Health.degraded();
        w.degradedReason = Health.degradedReason();
        w.classesInstrumented = Health.classesInstrumented();
        w.classesStripped = Health.classesStripped();
        w.stripFailures = Health.stripFailures();
        w.stripBlocked = Health.stripsBlocked();
        w.stripReArms = Health.stripReArms();

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
            w.coverage.add(c);
        }

        for (Map.Entry<Integer, Tier2Aggregator.MethodStats> e : aggregator.stats().entrySet()) {
            Tier2Aggregator.MethodStats s = e.getValue();
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
                    if (s.errorCounts[id] != 0) {
                        t.errorTypes.put(ErrorIds.name(id), Long.valueOf(s.errorCounts[id]));
                    }
                }
            }
            w.tier2.add(t);
        }
        return w;
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
            if (!CoverageSnapshot.allSet(e.getValue()) && !forcedStrips.contains(cls)) continue;
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
                    + " fully covered class(es)");
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
     * Ops/test hook: strip one class immediately, regardless of coverage.
     *
     * @return true only when probes were actually removed. A retransform the JVM accepted but
     *         that removed nothing returns false (G5-BUG-3) — it is exactly the case an operator
     *         reaching for this hook needs to be told about.
     */
    public boolean stripNow(Class<?> c) {
        if (instrumentation == null || c == null) return false;
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
