package io.auxin.agent.transport;

import io.auxin.agent.util.Json;

import java.util.Map;

/**
 * Serialises a {@link WindowPayload} to the CONTRACTS section 2 body. Hand-rolled: no Jackson.
 * Runs on the drain thread only.
 */
public final class Batch {

    public static String toJson(WindowPayload w) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        boolean first = true;

        first = Json.key(sb, first, "schemaVersion");
        sb.append(w.schemaVersion);
        first = Json.key(sb, first, "buildSha");
        Json.writeString(sb, w.buildSha);
        first = Json.key(sb, first, "artifact");
        Json.writeString(sb, w.artifact);
        first = Json.key(sb, first, "instanceId");
        Json.writeString(sb, w.instanceId);
        first = Json.key(sb, first, "windowStartMs");
        sb.append(w.windowStartMs);
        first = Json.key(sb, first, "windowEndMs");
        sb.append(w.windowEndMs);

        first = Json.key(sb, first, "agentHealth");
        sb.append('{');
        boolean h = true;
        h = Json.key(sb, h, "transformFailures");
        sb.append(w.transformFailures);
        h = Json.key(sb, h, "classesSkipped");
        sb.append('{');
        boolean s = true;
        for (Map.Entry<String, Long> e : w.classesSkipped.entrySet()) {
            s = Json.key(sb, s, e.getKey());
            sb.append(e.getValue().longValue());
        }
        sb.append('}');
        h = Json.key(sb, h, "ringDropped");
        sb.append(w.ringDropped);
        h = Json.key(sb, h, "clockNs");
        sb.append(w.clockNs);
        h = Json.key(sb, h, "clockDegraded");
        sb.append(w.clockDegraded);
        // BUG #25. clockNs + clockDegraded said the clock was slow; they never said what that
        // costs. full|sampled|disabled is about the TIMING only -- calls and errors are recorded
        // exactly in all three modes -- so the difference between "tier-2 is dead on this host"
        // and "you still get counts" is legible without reading the agent's source.
        h = Json.key(sb, h, "tier2TimingMode");
        Json.writeString(sb, w.tier2TimingMode);
        h = Json.key(sb, h, "degraded");
        sb.append(w.degraded);
        h = Json.key(sb, h, "degradedReason");
        Json.writeString(sb, w.degradedReason == null ? "" : w.degradedReason);
        // --- C50 additive fields ---
        h = Json.key(sb, h, "environment");
        Json.writeString(sb, w.environment);
        h = Json.key(sb, h, "livenessEvidence");
        sb.append(w.livenessEvidence);
        h = Json.key(sb, h, "testRunnerDetected");
        sb.append(w.testRunnerDetected);
        h = Json.key(sb, h, "classesInstrumented");
        sb.append(w.classesInstrumented);
        h = Json.key(sb, h, "classesStripped");
        sb.append(w.classesStripped);
        // --- G5 additive fields: every one of these was a silent failure before it existed ---
        h = Json.key(sb, h, "stripFailures");
        sb.append(w.stripFailures);
        h = Json.key(sb, h, "stripBlocked");
        sb.append(w.stripBlocked);
        h = Json.key(sb, h, "stripReArms");
        sb.append(w.stripReArms);
        h = Json.key(sb, h, "stripMaskMissing");
        sb.append(w.stripMaskMissing);
        // SCOPE-v3.1. Next to classesStripped on purpose: the two must be read together or the
        // first one means something it does not mean.
        h = Json.key(sb, h, "tier1bDisabledByTrace");
        sb.append(w.tier1bDisabledByTrace);
        h = Json.key(sb, h, "tier1bTraceBlockedClasses");
        sb.append(w.tier1bTraceBlockedClasses);
        h = Json.key(sb, h, "tier1bTraceScope");
        Json.writeString(sb, w.tier1bTraceScope == null ? "" : w.tier1bTraceScope);
        h = Json.key(sb, h, "scopeMatchedNothing");
        sb.append(w.scopeMatchedNothing);
        h = Json.key(sb, h, "classesLoadedTruncated");
        sb.append(w.classesLoadedTruncated);
        // --- SCOPE-v3 call-edge tier: additive, raw counts, inside agentHealth ---
        h = Json.key(sb, h, "edgesEnabled");
        sb.append(w.edgesEnabled);
        h = Json.key(sb, h, "edgesSampleRate");
        sb.append(w.edgesSampleRate);
        h = Json.key(sb, h, "edgesSampledRoots");
        sb.append(w.edgesSampledRoots);
        h = Json.key(sb, h, "edgesRecorded");
        sb.append(w.edgesRecorded);
        h = Json.key(sb, h, "edgesDropped");
        sb.append(w.edgesDropped);
        h = Json.key(sb, h, "edgesTruncatedDepth");
        sb.append(w.edgesTruncatedDepth);
        h = Json.key(sb, h, "edgesTruncatedRoot");
        sb.append(w.edgesTruncatedRoot);
        h = Json.key(sb, h, "edgesTruncatedDistinct");
        sb.append(w.edgesTruncatedDistinct);
        h = Json.key(sb, h, "edgeTierFailures");
        sb.append(w.edgeTierFailures);
        h = Json.key(sb, h, "edgeTracesReaped");
        sb.append(w.edgeTracesReaped);
        // BUG #26: armed, roots entered, nothing sampled. "No edges" is then a fact about
        // ax.edges.sample.rate, not about the application.
        h = Json.key(sb, h, "edgesStarvedOfSamples");
        sb.append(w.edgesStarvedOfSamples);
        sb.append('}');

        first = Json.key(sb, first, "classesLoaded");
        writeStrings(sb, w.classesLoaded);

        first = Json.key(sb, first, "instrumentedClasses");
        writeStrings(sb, w.instrumentedClasses);

        first = Json.key(sb, first, "coverage");
        sb.append('[');
        for (int i = 0; i < w.coverage.size(); i++) {
            WindowPayload.Coverage c = w.coverage.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            boolean cf = true;
            cf = Json.key(sb, cf, "class");
            Json.writeString(sb, c.className);
            cf = Json.key(sb, cf, "schemaHash");
            Json.writeString(sb, c.schemaHash == null ? "" : c.schemaHash);
            cf = Json.key(sb, cf, "probes");
            Json.writeString(sb, c.probesBase64);
            // Additive, alongside `probes` and packed identically. `probes` alone cannot say
            // whether a zero bit is "did not run" or "no probe was ever installed here", and a
            // bit nothing can set must never ship as evidence that a method never ran.
            cf = Json.key(sb, cf, "probesInstalled");
            Json.writeString(sb, c.probesInstalledBase64 == null ? "" : c.probesInstalledBase64);
            sb.append('}');
        }
        sb.append(']');

        // CONTRACTS section 2 v4 (BUG #24): ONE name table per window, resolving every
        // errorsByClass key below it. Written before tier2 so a streaming reader has the table
        // before the records that reference it. Keys are JSON strings because JSON has no
        // integer keys; a reader parses them to int and rejects a non-numeric key.
        //
        // Always present, empty when nothing threw: the same choice as edges[], for the same
        // reason -- an absent key means "this agent does not speak v4", which is a different
        // fact from "nothing errored in this window" and must not be read as it.
        first = Json.key(sb, first, "errorClasses");
        sb.append('{');
        boolean ec = true;
        for (Map.Entry<Integer, String> e : w.errorClasses.entrySet()) {
            ec = Json.key(sb, ec, String.valueOf(e.getKey().intValue()));
            Json.writeString(sb, e.getValue());
        }
        sb.append('}');

        first = Json.key(sb, first, "tier2");
        sb.append('[');
        for (int i = 0; i < w.tier2.size(); i++) {
            WindowPayload.Tier2 t = w.tier2.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            boolean tf = true;
            tf = Json.key(sb, tf, "class");
            Json.writeString(sb, t.className);
            tf = Json.key(sb, tf, "idx");
            sb.append(t.idx);
            tf = Json.key(sb, tf, "calls");
            sb.append(t.calls);
            tf = Json.key(sb, tf, "errors");
            sb.append(t.errors);
            tf = Json.key(sb, tf, "errorTypes");
            sb.append('{');
            boolean ef = true;
            for (Map.Entry<String, Long> e : t.errorTypes.entrySet()) {
                ef = Json.key(sb, ef, e.getKey());
                sb.append(e.getValue().longValue());
            }
            sb.append('}');
            // v4. Emitted ONLY when non-empty: an absent key is legal and means "types
            // unavailable", which a reader must render as that and never as zero types. Ids
            // resolve in THIS window's errorClasses and nowhere else.
            if (!t.errorsByClass.isEmpty()) {
                tf = Json.key(sb, tf, "errorsByClass");
                sb.append('{');
                boolean eb = true;
                for (Map.Entry<Integer, Long> e : t.errorsByClass.entrySet()) {
                    eb = Json.key(sb, eb, String.valueOf(e.getKey().intValue()));
                    sb.append(e.getValue().longValue());
                }
                sb.append('}');
            }
            tf = Json.key(sb, tf, "buckets");
            sb.append('[');
            for (int b = 0; b < t.buckets.length; b++) {
                if (b > 0) sb.append(',');
                sb.append(t.buckets[b]);
            }
            sb.append(']');
            tf = Json.key(sb, tf, "bucketScheme");
            Json.writeString(sb, t.bucketScheme);
            sb.append('}');
        }
        sb.append(']');

        // Additive alongside coverage and tier2 (SCOPE-v3). Always emitted, empty when the tier
        // is off: an absent key and a zero-length graph are different facts to a collector.
        first = Json.key(sb, first, "edges");
        sb.append('[');
        for (int i = 0; i < w.edges.size(); i++) {
            WindowPayload.Edge e = w.edges.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            boolean ef = true;
            ef = Json.key(sb, ef, "fromClass");
            Json.writeString(sb, e.fromClass);
            ef = Json.key(sb, ef, "fromIdx");
            sb.append(e.fromIdx);
            ef = Json.key(sb, ef, "toClass");
            Json.writeString(sb, e.toClass);
            ef = Json.key(sb, ef, "toIdx");
            sb.append(e.toIdx);
            ef = Json.key(sb, ef, "count");
            sb.append(e.count);
            sb.append('}');
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    private static void writeStrings(StringBuilder sb, java.util.List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            Json.writeString(sb, values.get(i));
        }
        sb.append(']');
    }

    private Batch() { throw new AssertionError(); }
}
