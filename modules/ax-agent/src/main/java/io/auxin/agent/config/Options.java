package io.auxin.agent.config;

import io.auxin.agent.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All agent configuration. Resolution order (later wins): built-in default, {@code -javaagent}
 * argument, system property {@code ax.*}, environment variable {@code GT_*}.
 *
 * <p>Agent-argument syntax follows JaCoCo's: options separated by {@code ,}, list values inside
 * one option separated by {@code :} — e.g.
 * {@code -javaagent:ax-agent.jar=include.packages=com.acme:com.other,environment=production}.
 * System properties may use either separator.
 *
 * <p>Three-level kill switch (PLAN-v2): {@link #enabled} (agent), {@link #tier1Enabled} /
 * {@link #tier2Enabled} (tier), {@link Scope} exclude list (package).
 */
public final class Options {

    /** {@code if (!probes[idx]) probes[idx] = true;} — 1 frame/probe, no store once covered. */
    public static final String PROBE_MODE_READ_THEN_STORE = "readthenstore";
    /** {@code probes[idx] = true;} — no branch, no frame, but a store on EVERY execution. */
    public static final String PROBE_MODE_BLIND = "blind";

    /** condy declared {@code Ljava/lang/Object;} + {@code CHECKCAST [Z} (JDK-8216970 safe). */
    public static final String CONDY_DESC_OBJECT = "object";
    /** condy declared {@code [Z}, no CHECKCAST: 3 bytes/probe cheaper, JDK 17-verified only. */
    public static final String CONDY_DESC_ARRAY = "array";

    /**
     * F2: the bridge keeps condy, with a synthetic {@code $axInit} bootstrap method on the
     * instrumented class itself (JaCoCo's {@code $jacocoInit} shape). No field, no
     * {@code <clinit>} edit, interfaces instrumentable, Tier-1b strip handle captured.
     */
    public static final String BRIDGE_SHAPE_SELF_BSM = "selfbsm";
    /**
     * The pre-F2 shape: a synthetic {@code $axProbes} field plus a {@code <clinit>} prologue.
     * Kept as a tested rollback exactly like {@code ax.probe.mode=blind}. Costs: interfaces
     * cannot be instrumented at all, and a bridged class can never be de-instrumented.
     */
    public static final String BRIDGE_SHAPE_FIELD = "field";

    public final boolean enabled;
    public final boolean tier1Enabled;
    public final boolean tier2Enabled;
    public final boolean stripEnabled;

    /**
     * G1 overturned PLAN-v2's blind store: at 10 threads blind costs +0.72-0.77 ns/probe (the
     * probe's cache line is taken Exclusive on every single execution) while read-then-store
     * costs ~0. The blind emitter stays in the build behind this flag so the decision stays
     * measurable and one system property away from being reversed.
     */
    public final String probeMode;

    /**
     * G3 found the {@code CHECKCAST [Z} is unnecessary on JDK 17 — declaring the condy directly
     * as {@code [Z} verifies and saves 3 bytes/probe. JDK-8216970 (a condy whose type is an
     * array descriptor) lived in Java 11, which we have NOT tested, so the default stays on the
     * workaround and this is opt-in per deployment.
     */
    public final boolean condyArrayDescriptor;

    /**
     * Install {@code java.lang.$Auxin} at premain so instrumented classes loaded by a
     * loader that cannot see the agent jar (OSGi, JBoss Modules, JPMS, some fat jars) can still
     * reach their probe array. Off => those classes are skipped, never broken.
     */
    public final boolean bridgeEnabled;

    /**
     * How the bridge delivers the probe array: {@link #BRIDGE_SHAPE_SELF_BSM} (default) or
     * {@link #BRIDGE_SHAPE_FIELD}. The field shape is the isolation suite's F2 defect —
     * no interfaces, no de-instrumentation — and is retained only as a rollback switch.
     */
    public final String bridgeShape;

    public final Scope scope;

    public final String manifestPath;
    public final String artifactOverride;
    public final String buildShaOverride;
    public final String instanceId;

    /** C50: the JVM's environment classification. Empty means unclassified => fail closed. */
    public final String environment;
    public final List<String> productionEnvironments;

    public final boolean transportEnabled;
    public final String collectorUrl;
    public final int collectorTimeoutMs;
    public final long flushIntervalMs;
    public final long drainIntervalMs;
    public final int ringCapacity;
    public final int stripMaxPerCycle;

    public final long startupCpuBudgetMs;
    public final long startupWallBudgetMs;

    public final String dumpDir;
    public final int logLevel;

    private Options(Map<String, String> a) {
        this.logLevel = Log.parseLevel(a.get("log.level"), Log.INFO);
        Log.setLevel(this.logLevel);

        this.enabled = bool(a, "enabled", true);
        this.tier1Enabled = bool(a, "tier1.enabled", true);
        this.tier2Enabled = bool(a, "tier2.enabled", true);
        this.stripEnabled = bool(a, "strip.enabled", true);
        this.probeMode = oneOf(a, "probe.mode", PROBE_MODE_READ_THEN_STORE,
                PROBE_MODE_READ_THEN_STORE, PROBE_MODE_BLIND);
        this.condyArrayDescriptor = CONDY_DESC_ARRAY.equals(
                oneOf(a, "condy.descriptor", CONDY_DESC_OBJECT, CONDY_DESC_OBJECT, CONDY_DESC_ARRAY));
        this.bridgeEnabled = bool(a, "bridge.enabled", true);
        this.bridgeShape = oneOf(a, "bridge.shape", BRIDGE_SHAPE_SELF_BSM,
                BRIDGE_SHAPE_SELF_BSM, BRIDGE_SHAPE_FIELD);

        // DEFAULT DENY. Unset ax.include.packages instruments nothing (PLAN-v2 C14).
        this.scope = new Scope(list(a, "include.packages"), list(a, "exclude.packages"));

        this.manifestPath = str(a, "manifest", "");
        this.artifactOverride = str(a, "artifact", "");
        this.buildShaOverride = str(a, "build.sha", "");
        this.instanceId = str(a, "instance.id", defaultInstanceId());

        this.environment = str(a, "environment", "").trim();
        List<String> prod = list(a, "environment.production.values");
        if (prod.isEmpty()) {
            prod = new ArrayList<String>();
            prod.add("production");
            prod.add("prod");
        }
        this.productionEnvironments = prod;

        this.transportEnabled = bool(a, "transport.enabled", true);
        this.collectorUrl = str(a, "collector.url", "");
        this.collectorTimeoutMs = (int) num(a, "collector.timeout.ms", 5000);
        this.flushIntervalMs = num(a, "flush.interval.ms", 60000);
        this.drainIntervalMs = num(a, "drain.interval.ms", 200);
        this.ringCapacity = pow2((int) num(a, "ring.capacity", 16384));
        this.stripMaxPerCycle = (int) num(a, "strip.max.per.cycle", 50);

        this.startupCpuBudgetMs = num(a, "startup.cpu.budget.ms", 5000);
        this.startupWallBudgetMs = num(a, "startup.wall.budget.ms", 120000);

        this.dumpDir = str(a, "dump.dir", "");
    }

    public static Options parse(String agentArgs) {
        Map<String, String> a = new LinkedHashMap<String, String>();
        if (agentArgs != null && agentArgs.length() > 0) {
            for (String kv : agentArgs.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) a.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        return new Options(a);
    }

    /** C50: is this JVM explicitly classified as production? */
    public boolean productionClassified() {
        if (environment.isEmpty()) return false;
        for (int i = 0; i < productionEnvironments.size(); i++) {
            if (productionEnvironments.get(i).equalsIgnoreCase(environment)) return true;
        }
        return false;
    }

    // ---- resolution helpers: sysprop > env var > agent arg > default ----

    private static String raw(Map<String, String> a, String key) {
        String sys = System.getProperty("ax." + key);
        if (sys != null) return sys;
        String env = System.getenv("GT_" + key.toUpperCase().replace('.', '_'));
        if (env != null) return env;
        return a.get(key);
    }

    private static String str(Map<String, String> a, String key, String def) {
        String v = raw(a, key);
        return v == null ? def : v.trim();
    }

    private static boolean bool(Map<String, String> a, String key, boolean def) {
        String v = raw(a, key);
        if (v == null) return def;
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
    }

    /** An unrecognised value must never silently change the probe shape: warn and use the default. */
    private static String oneOf(Map<String, String> a, String key, String def, String... allowed) {
        String v = raw(a, key);
        if (v == null) return def;
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < allowed.length; i++) {
            if (allowed[i].equals(v)) return v;
        }
        Log.warn("unknown value for ax." + key + ": '" + v + "', using " + def);
        return def;
    }

    private static long num(Map<String, String> a, String key, long def) {
        String v = raw(a, key);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            Log.warn("bad numeric value for ax." + key + ": '" + v + "', using " + def);
            return def;
        }
    }

    private static List<String> list(Map<String, String> a, String key) {
        List<String> out = new ArrayList<String>();
        String v = raw(a, key);
        if (v == null) return out;
        for (String part : v.split("[,:]")) {
            String p = part.trim();
            if (p.length() > 0) out.add(p);
        }
        return out;
    }

    private static int pow2(int n) {
        if (n < 256) n = 256;
        int p = Integer.highestOneBit(n);
        return p == n ? n : p << 1;
    }

    private static String defaultInstanceId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) host = System.getenv("POD_NAME");
        if (host == null || host.isEmpty()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Throwable t) {
                host = "unknown";
            }
        }
        String vm = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = vm.indexOf('@');
        String pid = at > 0 ? vm.substring(0, at) : vm;
        return host + "-" + pid;
    }

    public String summary() {
        return "enabled=" + enabled
                + " tier1=" + tier1Enabled
                + " tier2=" + tier2Enabled
                + " strip=" + stripEnabled
                + " probeMode=" + probeMode
                + " condyDescriptor=" + (condyArrayDescriptor ? CONDY_DESC_ARRAY : CONDY_DESC_OBJECT)
                + " bridge=" + bridgeEnabled
                + " bridgeShape=" + bridgeShape
                + " include=" + scope.includeDescription()
                + " exclude=" + scope.excludeDescription()
                + " environment=" + (environment.isEmpty() ? "<unset>" : environment)
                + " collector=" + (collectorUrl.isEmpty() ? "<none>" : collectorUrl)
                + " flushMs=" + flushIntervalMs
                + " ring=" + ringCapacity;
    }
}
