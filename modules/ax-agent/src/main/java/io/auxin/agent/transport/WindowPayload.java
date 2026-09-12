package io.auxin.agent.transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One flush window, exactly as specified by CONTRACTS section 2 (FROZEN).
 *
 * <p>C50 fields live inside {@code agentHealth} under the CANONICAL PINNED spelling:
 * {@code environment} (string), {@code livenessEvidence} (bool), {@code testRunnerDetected}
 * (bool). No synonyms. {@code agentHealth} is mandatory on every flush — the collector rejects a
 * payload without it — and a window with {@code livenessEvidence: false} or
 * {@code testRunnerDetected: true} is discarded at ingest and counted.
 *
 * <p>Percentiles are NEVER sent; {@code tier2[].buckets} are raw counts (C31).
 *
 * <p>NOT emitted: {@code coverage[].frames} (optional per-record test frames) — this agent does
 * not capture stack frames per coverage record.
 */
public final class WindowPayload {

    public static final class Coverage {
        public String className;
        public String schemaHash;
        public String probesBase64;   // packed bitset, LSB = idx 0, ACCUMULATED (never reset)
        /**
         * The INSTALLED-PROBE MASK for the same indices, same packing: bit {@code i} is set iff
         * the agent actually emitted a probe at index {@code i} in this JVM. Additive; always
         * present, so an absent key and an all-zero mask are different facts.
         *
         * <p><b>Why a second bitset.</b> {@link #probesBase64} is sized to every probe-eligible
         * method in the build manifest, but a probe is installed at only some of those indices.
         * A slot with no probe is never written by anything, so a zero there means "no evidence",
         * while a zero in a slot that DOES carry a probe means "this method did not run". Shipped
         * as one bitset the two are indistinguishable, which makes a permanently-zero bit look
         * like a dead method — the same class of error as gating the Tier-1b strip on it.
         *
         * <p>The C51 half of that was recoverable downstream by joining the build manifest's
         * {@code dynamicallyObservable} flag, and the collector does exactly that. The rest is
         * not: whether an entry stack map frame could be built safely, and whether
         * {@code ax.tier1.enabled} was on, are facts about THIS JVM that no manifest can carry.
         * So: {@code probes} is the liveness evidence, {@code probesInstalled & ~probes} is the
         * only set of indices this window may be read as evidence of death for, and
         * {@code ~probesInstalled} is silence.
         */
        public String probesInstalledBase64;
    }

    public static final class Tier2 {
        public String className;
        public int idx;
        public long calls;
        public long errors;
        public Map<String, Long> errorTypes = new LinkedHashMap<String, Long>();
        /**
         * CONTRACTS section 2 <b>v4</b> (BUG #24): errors of this record broken down by the
         * window-local id of {@link WindowPayload#errorClasses}, which is the only place the
         * ids may be resolved.
         *
         * <p>Emitted only when non-empty — an absent key is legal and means "types unavailable",
         * never "zero types". {@code sum(values) <= errors} and the two are NOT reconciled here:
         * {@code errors} is incremented unconditionally on the application thread, the id table
         * holds 254 classes and id 255 is the overflow bucket. A reader reports the remainder as
         * unattributed; an agent that "fixed" the difference would be inventing a class.
         */
        public Map<Integer, Long> errorsByClass = new LinkedHashMap<Integer, Long>();
        public long[] buckets = new long[0];   // indexed by log-linear bucket index
        public String bucketScheme = "loglinear-16-v1";
    }

    /**
     * One runtime caller-&gt;callee edge, additively counted (SCOPE-v3, CONTRACTS section 2).
     *
     * <p>Both ends are identified the way {@link Tier2} identifies its method: by
     * {@code (class, idx)} from the build-time manifest, never by an id of the agent's. Raw
     * counts only — the count is the number of times the edge was observed <b>in sampled
     * traces</b>, so the collector scales it by {@code agentHealth.edgesSampleRate}.
     */
    public static final class Edge {
        public String fromClass;
        public int fromIdx;
        public String toClass;
        public int toIdx;
        public long count;
    }

    /**
     * CONTRACTS v2. The collector rejects anything it does not recognise.
     *
     * <p><b>Still 2 after the edge tier was added, deliberately.</b> {@code edges[]} and the
     * {@code agentHealth.edges*} counters are purely additive and a v2 reader ignores unknown
     * keys, whereas the collector refuses any {@code schemaVersion} it does not know exactly
     * ({@code decode.py}: "schemaVersion N != 2; refusing to merge"). Bumping it here would make
     * every deployed collector drop every window, including its coverage — the document's
     * contract version is bumped instead.
     */
    public static final int WIRE_SCHEMA_VERSION = 2;

    public int schemaVersion = WIRE_SCHEMA_VERSION;
    public String buildSha = "";
    public String artifact = "";
    public String instanceId = "";
    public long windowStartMs;
    public long windowEndMs;

    // agentHealth
    public long transformFailures;
    public Map<String, Long> classesSkipped = new LinkedHashMap<String, Long>();
    public long ringDropped;
    public long clockNs;
    public boolean clockDegraded;
    /**
     * What the calibrated clock lets tier-2 do in this JVM: {@code full} (every call timed),
     * {@code sampled} (1 call in 64 timed) or {@code disabled} (nothing timed). BUG #25.
     *
     * <p><b>It is only about the timing.</b> {@code Tier2Aggregator} increments {@code calls}
     * unconditionally and classifies every error whatever the mode, so a {@code disabled} window
     * still carries exact call and error counts for every boundary method and loses only the
     * latency buckets. The field exists because the only way to learn that was to read the
     * agent's source: {@code clockDegraded: true} next to empty {@code buckets} reads as
     * "tier-2 is dead on this host" when it means "you still get counts".
     */
    public String tier2TimingMode = "full";
    public boolean degraded;
    public String degradedReason = "";
    public String environment = "unclassified";
    public boolean livenessEvidence;
    public boolean testRunnerDetected;
    public long classesInstrumented;
    /**
     * Successful Tier-1b strips: retransforms that actually removed probes.
     *
     * <p>A counter of operations, not of distinct classes — a class whose probes a foreign agent
     * reinstalls is stripped again, so {@code classesStripped - stripReArms} is the number of
     * distinct classes currently believed de-instrumented. Before G5-BUG-3 this counted strips
     * that removed nothing at all.
     */
    public long classesStripped;
    /** The Tier-1b retransform threw. */
    public long stripFailures;
    /** The Tier-1b retransform was accepted and removed nothing (G5-BUG-3). */
    public long stripBlocked;
    /** A foreign retransform put our probes back on a class we had stripped (G5 section 7). */
    public long stripReArms;
    /**
     * Tier-1b could not tell which of a class's probe slots carry a probe, so it left them
     * installed. Structurally impossible; counted rather than assumed. Cost only — coverage in
     * this window is unaffected, so this is deliberately not {@code degraded}.
     */
    public long stripMaskMissing;

    /**
     * SCOPE-v3.1: Tier-1b's auto-strip is suppressed for the intersection of the traced and
     * instrumented scopes, so {@code classesStripped} is NOT a statement that steady-state
     * overhead reached zero on this JVM.
     *
     * <p>Additive, so a v2 reader ignores it — but a reader that renders "overhead is now zero"
     * from {@code classesStripped} alone is wrong whenever this is true, which is the entire
     * reason it ships.
     */
    public boolean tier1bDisabledByTrace;

    /** The scope intersection responsible, dotted, e.g. {@code com.acme.search}. */
    public String tier1bTraceScope = "";

    /** Distinct classes Tier-1b declined to strip for that reason. */
    public long tier1bTraceBlockedClasses;
    /**
     * {@code ax.include.packages} is set and not one class has been instrumented (G5-BUG-1).
     * "I was configured to do work and did none" is not a clean run, and a window carrying this
     * flag must never be read as evidence that the application is dead.
     */
    public boolean scopeMatchedNothing;
    /** {@link #classesLoaded} hit its cap: absence from it no longer means "never loaded". */
    public boolean classesLoadedTruncated;

    // ---- call-edge tier (SCOPE-v3). All raw counts; all inside agentHealth. ----
    /** Is the edge tier on in this JVM? Off by default, so absence of edges is not a failure. */
    public boolean edgesEnabled;
    /**
     * 1-in-N root entries traced. Not a counter, but the window's edge counts cannot be
     * interpreted without it — the same reason {@code clockNs} lives here.
     */
    public int edgesSampleRate;
    /** Root invocations actually sampled. The denominator for every count in {@link #edges}. */
    public long edgesSampledRoots;
    /** Edges the drain thread folded into this window. */
    public long edgesRecorded;
    /** Edge events the ring rejected (drop-on-full, C27). */
    public long edgesDropped;
    /** Sampled root invocations that hit {@code ax.edges.max.depth}, one count each. */
    public long edgesTruncatedDepth;
    /** Sampled root invocations that hit {@code ax.edges.max.per.root}, one count each. */
    public long edgesTruncatedRoot;
    /** Distinct edges refused because {@code ax.edges.max.distinct} was reached. */
    public long edgesTruncatedDistinct;
    /**
     * The edge tier latched itself off after an unexpected Throwable. Its own counter, NOT
     * {@code degraded}: coverage and tier-2 in this window are still valid evidence.
     */
    public long edgeTierFailures;
    /** The drain thread reset a leaked trace gate. Non-zero means a trace was never closed. */
    public long edgeTracesReaped;
    /**
     * The edge tier is armed, boundary methods really were entered, and {@code edgesSampledRoots}
     * is still <b>zero</b> — so {@code edges[]} is empty because of the sample RATE and not
     * because nothing ran (BUG #26).
     *
     * <p>The production default of 1-in-1024 root entries (G6) needs ~1024 requests through one
     * boundary method before it records a single trace, which a developer poking at a service by
     * hand will never reach: the feature reads as broken while {@code edgesEnabled: true} says
     * it is on. This says which of the two it is, in the window itself.
     *
     * <p>Evidence that roots were entered is the tier-2 call count for this window — free, on the
     * drain thread, and not one instruction on any application path. With
     * {@code ax.tier2.enabled=false} there is no such evidence, so the flag stays false rather
     * than guessing: a missed warning, never a false one.
     */
    public boolean edgesStarvedOfSamples;

    /**
     * Every class the transformer was handed inside {@code ax.include.packages}, whether or not
     * it was instrumented (CONTRACTS section 2 name and meaning, C10).
     *
     * <p>Until G5-FINDING-4 this was built from the instrumented set, so any skip reason also
     * erased the class from it and "never loaded" was indistinguishable from "skipped" — which
     * is precisely the distinction C10 exists to give the analysis layer.
     */
    public List<String> classesLoaded = new ArrayList<String>();

    /** The subset of {@link #classesLoaded} that actually carries probes. Additive. */
    public List<String> instrumentedClasses = new ArrayList<String>();
    public List<Coverage> coverage = new ArrayList<Coverage>();
    public List<Tier2> tier2 = new ArrayList<Tier2>();

    /**
     * The window's exception-class name table (CONTRACTS section 2 v4, BUG #24): window-local id
     * -&gt; class name, resolving every key of every {@link Tier2#errorsByClass}.
     *
     * <p><b>Window-local.</b> Id 1 here is unrelated to id 1 in the next window, so a reader
     * resolves ids inside the window that carried them and stores names. Only ids this window's
     * records actually reference appear; id 255 is the overflow bucket, named
     * {@link io.auxin.agent.runtime.ErrorIds#OVERFLOW_NAME}.
     *
     * <p>Always emitted, empty when nothing errored — the same reason {@link #edges} is always
     * emitted: an absent key and an empty table are different facts, and a reader that sees the
     * key can tell "this agent speaks v4 and nothing threw" from "this agent predates v4".
     */
    public Map<Integer, String> errorClasses = new LinkedHashMap<Integer, String>();
    /** Additive, alongside {@link #coverage} and {@link #tier2}. Empty when the tier is off. */
    public List<Edge> edges = new ArrayList<Edge>();
}
