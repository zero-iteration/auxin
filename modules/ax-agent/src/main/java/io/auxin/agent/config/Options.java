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

    /** The canonical ingest route (CONTRACTS section 2). */
    public static final String INGEST_PATH = "/v1/ingest";

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
     * The runtime call-edge tier (SCOPE-v3). <b>OFF by default.</b>
     *
     * <p>It is the one tier that instruments every method twice (entry and every return) rather
     * than once, so it is opt-in per deployment even though the unsampled per-call cost is one
     * static load and one branch. Level two of the three-level kill switch, exactly like
     * {@link #tier1Enabled} / {@link #tier2Enabled}; level one is {@link #enabled} and level
     * three is the {@link Scope} exclude list, which the edge tier obeys because it is emitted
     * by the same transformer pass.
     */
    public final boolean edgesEnabled;

    /**
     * 1-in-N entries into a tier-2 boundary method are traced. Rounded up to a power of two so
     * the decision is {@code (++n & (N-1)) == 0} — one increment and one AND on a per-thread
     * counter, no clock, no random, no atomic. {@code 1} traces every root entry and exists for
     * the smoke suite, which must be deterministic; the production default is 1024.
     *
     * <p>When {@link #edgesSampleRateAuto} is set this is only the STARTING rate (still 1024, so
     * a JVM that never gets to its first flush behaves exactly like the fixed default) and the
     * drain thread retunes it per window.
     */
    public final int edgesSampleRate;

    /**
     * {@code ax.edges.sample.rate=auto} (BUG #26): target a sampled-root COUNT per flush window
     * instead of a fixed divisor. Opt-in — the default stays the fixed 1024 that G6 justified.
     *
     * <p>The rate is the whole reason the tier reads as broken in development: 1-in-1024 root
     * entries needs ~1024 requests through one boundary method before it records a single trace,
     * so a developer poking at a service by hand gets {@code edges: []} next to
     * {@code edgesEnabled: true}. A fixed divisor cannot serve both that and a 10k-rps pod; a
     * target count can. See {@code DrainThread.retuneEdgeSampleRate} for the arithmetic.
     */
    public final boolean edgesSampleRateAuto;

    /** Frames recorded per sampled root invocation. Past it, nothing is recorded and it is counted. */
    public final int edgesMaxDepth;

    /** Edges recorded per sampled root invocation. Past it, nothing is recorded and it is counted. */
    public final int edgesMaxPerRoot;

    /**
     * Distinct {@code (caller, callee)} pairs the drain thread will track. A cap, because the
     * window payload carries one JSON object per distinct edge and an application with a wide
     * dynamic-dispatch fan-out would otherwise decide our flush size for us. Refusals are
     * counted on the wire.
     */
    public final int edgesMaxDistinct;

    /** Capacity of the edge tier's own ring. Pre-allocated at premain, never resized. */
    public final int edgesRingCapacity;

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

    /**
     * C24's timing-mode thresholds, in nanoseconds of measured per-call clock cost — no longer
     * hardcoded (BUG #25). Above {@link #clockSampledThresholdNs} tier-2 times 1 call in 64;
     * above {@link #clockDisabledThresholdNs} it stops timing altogether. Calls and errors are
     * unaffected by either.
     *
     * <p>They are configuration because the measurement is architecture-dependent (VALIDATION
     * A4 / Linux L1-L2: a healthy arm64 host reads 41ns of pure timer granularity against a
     * 60ns threshold) and because an operator who knows their clocksource should be able to pin
     * the classification rather than re-litigate it on every boot.
     */
    public final long clockSampledThresholdNs;
    public final long clockDisabledThresholdNs;

    public static final long CLOCK_SAMPLED_THRESHOLD_DEFAULT_NS = 60;
    public static final long CLOCK_DISABLED_THRESHOLD_DEFAULT_NS = 200;

    /** The production edge sample rate (G6). Also the starting point for {@code =auto}. */
    public static final int EDGES_SAMPLE_RATE_DEFAULT = 1024;

    public final String dumpDir;
    public final int logLevel;

    private Options(Map<String, String> a) {
        this.logLevel = Log.parseLevel(a.get("log.level"), Log.INFO);
        Log.setLevel(this.logLevel);

        this.enabled = bool(a, "enabled", true);
        this.tier1Enabled = bool(a, "tier1.enabled", true);
        this.tier2Enabled = bool(a, "tier2.enabled", true);
        this.stripEnabled = bool(a, "strip.enabled", true);
        // OFF by default (SCOPE-v3): a new tier does not turn itself on in production.
        // ON BY DEFAULT (owner's decision, 2026-09-12). Justified by G6: the unsampled path costs
        // 0.028 / 0.092 / 0.340 ns per instrumented method at 1 / 4 / 10 threads, against the
        // 0.72-0.77 ns/probe that G1 rejected as too expensive for tier-1 -- so this is under half
        // of an already-rejected cost, and it is the only source of runtime call relationships now
        // that SCOPE-v3 makes auxin the sole agent.
        //
        // The cost that is NOT near-zero, and the reason to keep the switch: while ANY thread is
        // inside a sampled trace, EVERY other thread's calls fall through the gate into a
        // ThreadLocal read that finds nothing -- 0.25 ns/call site at 1 thread, 1.56 ns at 10. A
        // deployment with many threads and long traces sits nearer that figure than the unsampled
        // one. Raise ax.edges.sample.rate, or set ax.edges.enabled=false, if that shows up.
        this.edgesEnabled = bool(a, "edges.enabled", true);
        // ax.edges.sample.rate is a number OR the literal `auto` (BUG #26). Checked as a string
        // first because num() warns about anything unparseable, and "auto" is not a mistake.
        final String rawRate = str(a, "edges.sample.rate", "");
        this.edgesSampleRateAuto = rawRate.equalsIgnoreCase("auto");
        this.edgesSampleRate = edgesSampleRateAuto
                ? EDGES_SAMPLE_RATE_DEFAULT
                : pow2From(num(a, "edges.sample.rate", EDGES_SAMPLE_RATE_DEFAULT), 1,
                        "edges.sample.rate");
        this.edgesMaxDepth = (int) clamp(num(a, "edges.max.depth", 32), 1, 4096, "edges.max.depth");
        this.edgesMaxPerRoot = (int) clamp(num(a, "edges.max.per.root", 256), 0, 1 << 20,
                "edges.max.per.root");
        this.edgesMaxDistinct = (int) clamp(num(a, "edges.max.distinct", 8192), 1, 1 << 20,
                "edges.max.distinct");
        this.edgesRingCapacity = pow2((int) num(a, "edges.ring.capacity", 16384));
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
        // BUG #23: HttpSender's javadoc says "POST /v1/ingest" but it posts to this URL RAW, so a
        // natural `ax.collector.url=http://host:8080` posted to `/` while the collector serves
        // `/v1/ingest` -- every window 404'd, silently, and the circuit breaker then opened. Found
        // the first time the real agent was pointed at the real collector. Fourth bug of this exact
        // shape: two components each verified against a stand-in for the other.
        //
        // A bare authority now gets the canonical ingest path appended, announced once. An explicit
        // path is honoured untouched, so a reverse proxy or a custom mount still works.
        String cu = str(a, "collector.url", "");
        if (!cu.isEmpty()) {
            int scheme = cu.indexOf("://");
            String afterAuthority = scheme < 0 ? "" : cu.substring(scheme + 3);
            int slash = afterAuthority.indexOf('/');
            String path = slash < 0 ? "" : afterAuthority.substring(slash);
            if (path.isEmpty() || path.equals("/")) {
                String base = slash < 0 ? cu : cu.substring(0, cu.length() - path.length());
                cu = base + INGEST_PATH;
                Log.info("collector.url had no path: posting to " + cu
                        + " (set an explicit path to override)");
            }
        }
        this.collectorUrl = cu;
        this.collectorTimeoutMs = (int) num(a, "collector.timeout.ms", 5000);
        this.flushIntervalMs = num(a, "flush.interval.ms", 60000);
        this.drainIntervalMs = num(a, "drain.interval.ms", 200);
        this.ringCapacity = pow2((int) num(a, "ring.capacity", 16384));
        this.stripMaxPerCycle = (int) num(a, "strip.max.per.cycle", 50);

        this.startupCpuBudgetMs = num(a, "startup.cpu.budget.ms", 5000);
        this.startupWallBudgetMs = num(a, "startup.wall.budget.ms", 120000);

        long sampledNs = clamp(num(a, "clock.sampled.threshold.ns",
                CLOCK_SAMPLED_THRESHOLD_DEFAULT_NS), 0, 1000000, "clock.sampled.threshold.ns");
        long disabledNs = clamp(num(a, "clock.disabled.threshold.ns",
                CLOCK_DISABLED_THRESHOLD_DEFAULT_NS), 0, 1000000, "clock.disabled.threshold.ns");
        if (sampledNs > disabledNs) {
            // Inverted thresholds would make the "disabled" band unreachable and quietly leave a
            // 400ns Xen clock timing every call. Say so and keep the stricter of the two.
            Log.warn("ax.clock.sampled.threshold.ns=" + sampledNs + " is above "
                    + "ax.clock.disabled.threshold.ns=" + disabledNs + ", which would make the "
                    + "disabled band unreachable: using " + disabledNs + " for both.");
            sampledNs = disabledNs;
        }
        this.clockSampledThresholdNs = sampledNs;
        this.clockDisabledThresholdNs = disabledNs;

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

    /**
     * Rounds up to a power of two, out loud. The sampling decision is a mask, so a rate of 1000
     * is not representable — silently sampling 1-in-1024 while the operator believes 1-in-1000
     * would make every edge count on the wire wrong by 2.4% with nothing to point at.
     */
    private static int pow2From(long raw, int min, String key) {
        long n = raw < min ? min : raw;
        if (n > (1L << 30)) n = 1L << 30;
        int p = Integer.highestOneBit((int) n);
        int out = p == (int) n ? p : p << 1;
        if (out != raw) {
            Log.warn("ax." + key + "=" + raw + " is not a power of two (the sampling decision is "
                    + "a bitmask): using " + out + ". Edge counts on the wire scale by this "
                    + "number, so it is reported as agentHealth.edgesSampleRate.");
        }
        return out;
    }

    private static long clamp(long v, long lo, long hi, String key) {
        if (v < lo || v > hi) {
            long out = v < lo ? lo : hi;
            Log.warn("ax." + key + "=" + v + " is out of range [" + lo + ".." + hi + "]: using "
                    + out);
            return out;
        }
        return v;
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
                + " edges=" + edgesEnabled
                + (edgesEnabled ? " edgesSampleRate=" + edgesSampleRate
                        + (edgesSampleRateAuto ? "(auto)" : "")
                        + " edgesMaxDepth=" + edgesMaxDepth
                        + " edgesMaxPerRoot=" + edgesMaxPerRoot : "")
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
