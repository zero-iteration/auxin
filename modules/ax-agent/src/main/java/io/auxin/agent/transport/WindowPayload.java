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
    }

    public static final class Tier2 {
        public String className;
        public int idx;
        public long calls;
        public long errors;
        public Map<String, Long> errorTypes = new LinkedHashMap<String, Long>();
        public long[] buckets = new long[0];   // indexed by log-linear bucket index
        public String bucketScheme = "loglinear-16-v1";
    }

    /** CONTRACTS v2. The collector rejects anything it does not recognise. */
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
     * {@code ax.include.packages} is set and not one class has been instrumented (G5-BUG-1).
     * "I was configured to do work and did none" is not a clean run, and a window carrying this
     * flag must never be read as evidence that the application is dead.
     */
    public boolean scopeMatchedNothing;
    /** {@link #classesLoaded} hit its cap: absence from it no longer means "never loaded". */
    public boolean classesLoadedTruncated;

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
}
