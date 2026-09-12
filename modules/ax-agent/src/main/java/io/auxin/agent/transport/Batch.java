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
        h = Json.key(sb, h, "scopeMatchedNothing");
        sb.append(w.scopeMatchedNothing);
        h = Json.key(sb, h, "classesLoadedTruncated");
        sb.append(w.classesLoadedTruncated);
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
            sb.append('}');
        }
        sb.append(']');

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
