package io.auxin.trace.config;

import io.auxin.trace.util.TLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All tracer configuration, under the {@code ax.trace.*} namespace so that no property of the
 * trace tier can be confused with a property of ax-agent's other tiers.
 *
 * <p>SCOPE-v3.1: this is parsed by {@link io.auxin.agent.config.Options}, from the SAME
 * {@code -javaagent} argument map, so there is one premain, one argument string and one
 * resolution order (later wins): built-in default, {@code -javaagent:ax-agent.jar=trace.k=v},
 * system property {@code ax.trace.*}, environment variable {@code AX_TRACE_*} or
 * {@code GT_TRACE_*}.
 *
 * <p>The agent's own kill switch is upstream of this one: {@code ax.enabled=false} disables the
 * whole agent, tracer included, and {@link #enabled} is level two of the same three-level kill
 * switch that carries {@code ax.tier1.enabled} / {@code ax.tier2.enabled} /
 * {@code ax.edges.enabled}. Level three is the {@link TraceScope} exclude list.
 *
 * <h3>The master switch is OFF</h3>
 * {@link #enabled} defaults to <b>false</b> and nothing — not the servlet entry, not one probe —
 * is installed when it is false. That is not the usual "a new tier is opt-in" caution. A trace
 * probe records values and cannot be stripped, so an accidentally-on tracer is simultaneously a
 * permanent overhead and a data-exposure surface. See TRADE-OFFS.md.
 */
public final class TraceOptions {

    /** The trace document route. Deliberately NOT {@code /v1/ingest} (TRACE-CONTRACT.md). */
    public static final String TRACE_PATH = "/v1/trace";

    /** The activation header. A header and not a query parameter — see TRACE-CONTRACT.md §1. */
    public static final String HEADER = "X-Auxin-Trace";

    /**
     * The pre-merge default, kept reachable: refuse to arm the tracer at all while Tier-1b's
     * auto-strip covers the traced scope. Nothing is instrumented and ax-agent keeps every
     * property it promised.
     */
    public static final String CONFLICT_REFUSE = "refuse";
    /**
     * THE DEFAULT since SCOPE-v3.1 (owner's decision: <i>"we can have one agent only, its fine
     * if it adds overhead for some requests"</i>). Arm the tracer and disable Tier-1b's
     * auto-strip <b>for the intersection of the traced and instrumented scopes only</b> --
     * automatically, and loudly. A class outside the traced scope still strips normally.
     */
    public static final String CONFLICT_DISABLE_STRIP = "disable-strip";
    /**
     * Arm both. Tier-1b strips traced classes too, which removes only the coverage probes and
     * leaves the trace probes on the hot path: a true {@code classesStripped} counter and a
     * false "overhead is now zero" conclusion. Opt-in, and reported on the wire.
     */
    public static final String CONFLICT_ALLOW = "allow";

    /** Record every conditional. Honest, and unreadable on a real service. */
    public static final String BRANCHES_ALL = "all";
    /** Record a conditional whose operands came from a call or a field read. The default. */
    public static final String BRANCHES_PREDICATES = "predicates";
    public static final String BRANCHES_NONE = "none";

    public final boolean enabled;
    public final TraceScope scope;
    /** Extra packages searched for a servlet entry, beyond {@link #scope}. Still default-deny. */
    public final TraceScope entryScope;

    public final String headerName;
    /**
     * A shared secret the header value must equal. Empty means any {@code X-Auxin-Trace: 1}
     * activates a trace, which is only acceptable outside production — see
     * {@link #tokenRequiredButMissing()}.
     */
    public final String token;

    public final int ratePerMinute;
    public final int maxConcurrent;
    public final int maxFrames;
    public final int maxDepth;
    public final int maxObsPerFrame;
    public final int maxArmsPerFrame;
    public final boolean collapsePassThroughs;

    public final String branches;
    public final boolean observeParams;
    public final boolean observeReturns;
    public final boolean observeParamsAtExit;
    public final boolean propagateExecutors;
    public final boolean parallelStreamWindow;
    public final boolean recordPath;
    public final boolean recordTimings;

    public final String projectionPath;
    public final List<String> redactAllow;

    /** Extra {@code name(desc)} servlet-entry signatures (TRACE-CONTRACT.md section 1). */
    public final List<String> entrySignatures;

    public final String stripConflict;

    public final boolean transportEnabled;
    public final String collectorUrl;
    public final int collectorTimeoutMs;
    public final int queueCapacity;

    public final String environment;
    public final List<String> productionEnvironments;

    public final String artifact;
    public final String instanceId;
    public final String dumpDir;
    public final int logLevel;

    private TraceOptions(Map<String, String> a, int agentLogLevel) {
        // One agent, one log level by default: ax.log.level governs both unless ax.trace.log.level
        // is set explicitly.
        this.logLevel = TLog.parseLevel(str(a, "log.level", ""), agentLogLevel);
        TLog.setLevel(this.logLevel);

        this.enabled = bool(a, "enabled", false);          // MASTER SWITCH, DEFAULT OFF
        this.scope = new TraceScope(list(a, "include.packages"), list(a, "exclude.packages"));
        this.entryScope = new TraceScope(list(a, "entry.packages"), list(a, "exclude.packages"));

        this.headerName = str(a, "header", HEADER);
        this.token = str(a, "token", "");

        this.ratePerMinute = (int) clamp(num(a, "rate.per.minute", 5), 0, 10000,
                "rate.per.minute");
        this.maxConcurrent = (int) clamp(num(a, "max.concurrent", 1), 1, 64, "max.concurrent");
        this.maxFrames = (int) clamp(num(a, "max.frames", 20000), 1, 1 << 20, "max.frames");
        this.maxDepth = (int) clamp(num(a, "max.depth", 256), 1, 65535, "max.depth");
        this.maxObsPerFrame = (int) clamp(num(a, "max.obs.per.frame", 64), 0, 4096,
                "max.obs.per.frame");
        this.maxArmsPerFrame = (int) clamp(num(a, "max.arms.per.frame", 64), 0, 4096,
                "max.arms.per.frame");
        this.collapsePassThroughs = bool(a, "collapse.passthroughs", true);

        this.branches = oneOf(a, "branches", BRANCHES_PREDICATES,
                BRANCHES_ALL, BRANCHES_PREDICATES, BRANCHES_NONE);
        this.observeParams = bool(a, "observe.params", true);
        this.observeReturns = bool(a, "observe.returns", true);
        this.observeParamsAtExit = bool(a, "observe.params.at.exit", true);
        this.propagateExecutors = bool(a, "propagate.executors", true);
        this.parallelStreamWindow = bool(a, "parallelstream.window", true);
        this.recordPath = bool(a, "record.path", true);
        this.recordTimings = bool(a, "record.timings", true);

        this.projectionPath = str(a, "projection", "");
        this.redactAllow = list(a, "redact.allow");
        this.entrySignatures = list(a, "entry.signatures");

        // SCOPE-v3.1: disable-strip is the default. The trade is TAKEN, not refused -- but it
        // is taken narrowly (the scope intersection, not the JVM) and loudly (a premain WARN, a
        // startup-summary field and tier1bDisabledByTrace on the wire).
        this.stripConflict = oneOf(a, "strip.conflict", CONFLICT_DISABLE_STRIP,
                CONFLICT_REFUSE, CONFLICT_DISABLE_STRIP, CONFLICT_ALLOW);

        this.transportEnabled = bool(a, "transport.enabled", true);
        String cu = str(a, "collector.url", "");
        if (!cu.isEmpty()) {
            // BUG #23's shape, avoided at the source: a bare authority gets the canonical trace
            // path appended, announced once. ax-agent learned this the hard way against its own
            // collector; there is no reason to relearn it.
            int scheme = cu.indexOf("://");
            String afterAuthority = scheme < 0 ? "" : cu.substring(scheme + 3);
            int slash = afterAuthority.indexOf('/');
            String path = slash < 0 ? "" : afterAuthority.substring(slash);
            if (path.isEmpty() || path.equals("/")) {
                String base = slash < 0 ? cu : cu.substring(0, cu.length() - path.length());
                cu = base + TRACE_PATH;
                TLog.info("ax.trace.collector.url had no path: posting trace documents to " + cu);
            }
        }
        this.collectorUrl = cu;
        this.collectorTimeoutMs = (int) num(a, "collector.timeout.ms", 5000);
        this.queueCapacity = (int) clamp(num(a, "queue.capacity", 16), 1, 4096, "queue.capacity");

        // Read from ax.environment when ax.trace.environment is unset, so that one classification
        // governs both agents. This is a READ of a property ax-agent also reads; it is not a
        // dependency on ax-agent and works whether or not ax-agent is present.
        String env = str(a, "environment", "");
        if (env.isEmpty()) {
            String shared = System.getProperty("ax.environment");
            if (shared == null) shared = System.getenv("GT_ENVIRONMENT");
            env = shared == null ? "" : shared.trim();
        }
        this.environment = env;
        List<String> prod = list(a, "environment.production.values");
        if (prod.isEmpty()) {
            prod = new ArrayList<String>();
            prod.add("production");
            prod.add("prod");
        }
        this.productionEnvironments = prod;

        this.artifact = str(a, "artifact", "");
        this.instanceId = str(a, "instance.id", defaultInstanceId());
        this.dumpDir = str(a, "dump.dir", "");
    }

    /**
     * Parsed from the ONE agent argument map {@link io.auxin.agent.config.Options} already
     * built. A trace key appears there under a {@code trace.} prefix --
     * {@code -javaagent:ax-agent.jar=include.packages=com.acme,trace.enabled=true} -- which is
     * the same shape as the system property ({@code ax.include.packages} /
     * {@code ax.trace.enabled}) and needs no second argument string.
     */
    public static TraceOptions parse(Map<String, String> agentArgs, int agentLogLevel) {
        Map<String, String> a = new LinkedHashMap<String, String>();
        if (agentArgs != null) {
            for (Map.Entry<String, String> e : agentArgs.entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith("trace.")) a.put(k.substring(6), e.getValue());
            }
        }
        return new TraceOptions(a, agentLogLevel);
    }

    public boolean productionClassified() {
        if (environment.isEmpty()) return false;
        for (int i = 0; i < productionEnvironments.size(); i++) {
            if (productionEnvironments.get(i).equalsIgnoreCase(environment)) return true;
        }
        return false;
    }

    /**
     * An unauthenticated trace header is both an amplification vector (any caller can make the
     * service do 10-100x the work on a request) and a data-exposure vector (any caller can ask
     * for a structural trace of their own request). In production, refusing to arm without a
     * token is the only defensible default.
     */
    public boolean tokenRequiredButMissing() {
        return productionClassified() && token.isEmpty();
    }

    // ---- resolution helpers: sysprop > env var > agent arg > default ----

    private static String raw(Map<String, String> a, String key) {
        String sys = System.getProperty("ax.trace." + key);
        if (sys != null) return sys;
        String suffix = key.toUpperCase(Locale.ROOT).replace('.', '_');
        String env = System.getenv("AX_TRACE_" + suffix);
        if (env == null) env = System.getenv("GT_TRACE_" + suffix);   // ax-agent's env prefix
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

    private static String oneOf(Map<String, String> a, String key, String def, String... allowed) {
        String v = raw(a, key);
        if (v == null) return def;
        v = v.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < allowed.length; i++) {
            if (allowed[i].equals(v)) return v;
        }
        TLog.warn("unknown value for ax.trace." + key + ": '" + v + "', using " + def);
        return def;
    }

    private static long num(Map<String, String> a, String key, long def) {
        String v = raw(a, key);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            TLog.warn("bad numeric value for ax.trace." + key + ": '" + v + "', using " + def);
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

    private static long clamp(long v, long lo, long hi, String key) {
        if (v < lo || v > hi) {
            long out = v < lo ? lo : hi;
            TLog.warn("ax.trace." + key + "=" + v + " is out of range [" + lo + ".." + hi
                    + "]: using " + out);
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
        return host + "-" + (at > 0 ? vm.substring(0, at) : vm);
    }

    public String summary() {
        return "enabled=" + enabled
                + " include=" + scope.includeDescription()
                + " entryPackages=" + entryScope.includeDescription()
                + " exclude=" + scope.excludeDescription()
                + " header=" + headerName
                + " token=" + (token.isEmpty() ? "<none>" : "set(" + token.length() + " chars)")
                + " ratePerMinute=" + ratePerMinute
                + " maxConcurrent=" + maxConcurrent
                + " branches=" + branches
                + " observeParams=" + observeParams
                + " observeReturns=" + observeReturns
                + " observeParamsAtExit=" + observeParamsAtExit
                + " propagateExecutors=" + propagateExecutors
                + " parallelStreamWindow=" + parallelStreamWindow
                + " collapsePassThroughs=" + collapsePassThroughs
                + " maxFrames=" + maxFrames
                + " maxDepth=" + maxDepth
                + " stripConflict=" + stripConflict
                + " environment=" + (environment.isEmpty() ? "<unset>" : environment)
                + " collector=" + (collectorUrl.isEmpty() ? "<none>" : collectorUrl);
    }
}
