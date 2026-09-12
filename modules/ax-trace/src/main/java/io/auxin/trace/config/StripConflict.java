package io.auxin.trace.config;

import io.auxin.trace.util.TLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * THE MUTUAL EXCLUSION. {@code ax.trace.enabled=true} and ax-agent's Tier-1b auto-strip cannot
 * both be true for the same classes, and a user must never silently lose the zero-overhead
 * property they were promised.
 *
 * <h3>Why it is a genuine contradiction and not a tuning conflict</h3>
 * Tier-1b's entire claim is "steady state reaches literally zero": once every probe in a class
 * has been set, the stripper retransforms the class and removes the probe instructions, so the
 * hot path is byte-for-byte the original code. A <b>trace</b> probe cannot participate in that,
 * because the condition Tier-1b waits for does not exist — a coverage probe is done once it has
 * fired, a trace probe is needed on the one future request nobody has made yet. So for any class
 * in the traced scope, "steady state → zero" is false while the tracer is armed. Not slower:
 * <i>false</i>.
 *
 * <p>Worse, the two mechanisms do not even conflict <i>visibly</i>. Tier-1b would still strip
 * successfully — it matches tier-1 probe shapes and nothing in this module looks like one — so
 * the operator would see {@code classesStripped} rising, read it as "overhead is now zero", and
 * be wrong by however much the trace probes cost. The failure mode is a true counter and a false
 * conclusion, which is the hardest kind to notice.
 *
 * <h3>The three policies</h3>
 * <ul>
 *   <li>{@code refuse} (DEFAULT) — the tracer does not arm. ax-agent keeps every property it
 *       promised; the operator is told exactly which two settings collide and which one to
 *       change. Refusing the NEW thing rather than silently degrading the OLD one is the only
 *       safe default: someone who set {@code ax.trace.enabled=true} in a hurry did not intend
 *       to change ax-agent's cost profile.</li>
 *   <li>{@code disable-strip} — arm the tracer and turn Tier-1b off, saying so in the startup
 *       line. This works by setting {@code ax.strip.enabled=false} before ax-agent reads it,
 *       which requires {@code -javaagent:ax-trace.jar} to come FIRST on the command line. It is
 *       then <b>verified</b>, not assumed (see {@link #verify}).</li>
 *   <li>{@code allow} — arm both and accept the trade. Requires the operator to have said so
 *       explicitly, and it is reported in every trace document's health block.</li>
 * </ul>
 *
 * <h3>Detection is scope-aware</h3>
 * There is no conflict at all when the two scopes do not overlap: tracing {@code com.acme.search}
 * while ax-agent covers {@code com.acme.billing} leaves billing's Tier-1b claim intact. Only the
 * intersection matters, and it is computed and printed.
 */
public final class StripConflict {

    /** ax-agent's properties, read by name. This module does NOT depend on ax-agent. */
    private static final String AGENT_STRIP = "ax.strip.enabled";
    private static final String AGENT_INCLUDE = "ax.include.packages";
    private static final String AGENT_ENABLED = "ax.enabled";
    private static final String AGENT_TIER1 = "ax.tier1.enabled";

    public final boolean agentPresent;
    public final boolean stripRequested;
    public final List<String> overlap;
    public final boolean conflicts;
    public final String policy;
    public final boolean armTracer;
    public final boolean strippedDisabled;

    private StripConflict(boolean agentPresent, boolean stripRequested, List<String> overlap,
                          String policy, boolean armTracer, boolean strippedDisabled) {
        this.agentPresent = agentPresent;
        this.stripRequested = stripRequested;
        this.overlap = overlap;
        this.conflicts = agentPresent && stripRequested && !overlap.isEmpty();
        this.policy = policy;
        this.armTracer = armTracer;
        this.strippedDisabled = strippedDisabled;
    }

    public static StripConflict detect(TraceOptions o) {
        final boolean agentPresent = agentOnClasspath();
        final boolean agentEnabled = prop(AGENT_ENABLED, true);
        final boolean tier1 = prop(AGENT_TIER1, true);
        // ax-agent defaults ax.strip.enabled to TRUE, so ABSENCE of the property means the
        // strip IS on. Reading the absence as "off" would turn this whole check into a no-op in
        // exactly the default configuration.
        final boolean strip = prop(AGENT_STRIP, true) && agentEnabled && tier1;
        final List<String> overlap = overlap(o, System.getProperty(AGENT_INCLUDE));

        if (!agentPresent || !strip || overlap.isEmpty()) {
            return new StripConflict(agentPresent, strip, overlap, "none", true, false);
        }

        if (TraceOptions.CONFLICT_ALLOW.equals(o.stripConflict)) {
            TLog.warn(banner(overlap)
                    + "\n  ax.trace.strip.conflict=allow: BOTH are armed. Tier-1b will report "
                    + "successful strips for these classes and their steady-state overhead will "
                    + "NOT be zero -- the trace probes remain. This was asked for explicitly and "
                    + "is reported in every trace document's health block.");
            return new StripConflict(true, true, overlap, TraceOptions.CONFLICT_ALLOW, true, false);
        }
        if (TraceOptions.CONFLICT_DISABLE_STRIP.equals(o.stripConflict)) {
            System.setProperty(AGENT_STRIP, "false");
            TLog.warn(banner(overlap)
                    + "\n  ax.trace.strip.conflict=disable-strip: Tier-1b auto-strip is being "
                    + "turned OFF for this JVM (ax.strip.enabled=false) so that the tracer can "
                    + "arm. ax-agent's coverage, tier-2 and the call-edge tier are unaffected; "
                    + "only the de-instrumentation step is."
                    + "\n  THIS ONLY WORKS IF -javaagent:ax-trace.jar PRECEDES "
                    + "-javaagent:ax-agent.jar on the command line. It is verified a few seconds "
                    + "after startup and escalated to health.stripConflictUnresolved if not.");
            return new StripConflict(true, true, overlap, TraceOptions.CONFLICT_DISABLE_STRIP,
                    true, true);
        }
        TLog.warn(banner(overlap)
                + "\n  ax.trace.strip.conflict=refuse (the default): THE TRACER IS NOT ARMED. "
                + "Nothing is instrumented and no request can be traced. ax-agent keeps every "
                + "property it promised."
                + "\n  To trace anyway, pick one:"
                + "\n    - ax.strip.enabled=false                   (give up Tier-1b, keep coverage)"
                + "\n    - ax.trace.strip.conflict=disable-strip    (the same, done for you)"
                + "\n    - ax.trace.strip.conflict=allow            (keep both, accept the trade)"
                + "\n    - narrow ax.trace.include.packages so it does not overlap "
                + AGENT_INCLUDE);
        return new StripConflict(true, true, overlap, TraceOptions.CONFLICT_REFUSE, false, false);
    }

    private static String banner(List<String> overlap) {
        return "TIER-1b / TRACE CONFLICT."
                + "\n  ax.trace.enabled=true and ax-agent's Tier-1b auto-strip both cover: "
                + join(overlap)
                + "\n  A TRACE PROBE CAN NEVER BE STRIPPED -- you cannot know in advance which "
                + "request will be traced -- so Tier-1b's \"steady state reaches zero\" is FALSE "
                + "for these classes while the tracer is armed. Tier-1b would still report "
                + "successful strips, which is a true counter and a false conclusion.";
    }

    /**
     * Re-reads ax-agent's LIVE configuration after both premains have run, reflectively and
     * read-only, and escalates when {@code disable-strip} did not take.
     *
     * <p>This exists because setting a system property from one premain and hoping another
     * premain has not read it yet is an ordering assumption, and an ordering assumption that is
     * never checked is the shape of four bugs already recorded in PLAN-v2 ("two components each
     * verified against a stand-in for the other"). So it is checked against the real thing.
     *
     * @return true when the conflict is genuinely resolved.
     */
    public boolean verify() {
        if (!strippedDisabled) return !conflicts || TraceOptions.CONFLICT_ALLOW.equals(policy);
        try {
            Class<?> agent = Class.forName("io.auxin.agent.AuxinAgent", false,
                    StripConflict.class.getClassLoader());
            Method optionsOf = agent.getMethod("options");
            Object opts = optionsOf.invoke(null);
            if (opts == null) return true;             // ax-agent never armed: no strip to fight
            Field f = opts.getClass().getField("stripEnabled");
            boolean live = f.getBoolean(opts);
            if (!live) {
                TLog.info("verified against the live ax-agent: Tier-1b auto-strip is OFF, so the "
                        + "traced scope keeps its trace probes and nothing claims otherwise.");
                return true;
            }
            TLog.warn("ax.trace.strip.conflict=disable-strip DID NOT TAKE: the live ax-agent "
                    + "still reports stripEnabled=true, which means it read ax.strip.enabled "
                    + "before this agent set it. Put -javaagent:ax-trace.jar BEFORE "
                    + "-javaagent:ax-agent.jar, or set -Dax.strip.enabled=false on the command "
                    + "line. Tier-1b will report strips for traced classes whose steady-state "
                    + "overhead is not zero. Counted as health.stripConflictUnresolved.");
            return false;
        } catch (ClassNotFoundException e) {
            return true;                               // ax-agent is not in this JVM
        } catch (Throwable t) {
            TLog.warn("could not verify ax-agent's live strip setting (" + t + "). Treating the "
                    + "Tier-1b conflict as UNRESOLVED, which is the safe direction.");
            return false;
        }
    }

    private static boolean agentOnClasspath() {
        try {
            Class.forName("io.auxin.agent.AuxinAgent", false,
                    StripConflict.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean prop(String key, boolean def) {
        String v = System.getProperty(key);
        if (v == null) {
            v = System.getenv("GT_" + key.substring(3).toUpperCase(java.util.Locale.ROOT)
                    .replace('.', '_'));
        }
        if (v == null) return def;
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equals("1");
    }

    /**
     * The intersection of the two scopes, as package prefixes. Either containing the other counts
     * — {@code com.acme} covers {@code com.acme.search} and vice versa.
     */
    static List<String> overlap(TraceOptions o, String agentInclude) {
        List<String> out = new ArrayList<String>();
        if (agentInclude == null || agentInclude.trim().isEmpty()) return out;
        List<String> mine = o.scope.includeDotted();
        for (String theirsRaw : agentInclude.split("[,:]")) {
            String theirs = theirsRaw.trim();
            if (theirs.isEmpty()) continue;
            for (int i = 0; i < mine.size(); i++) {
                String m = mine.get(i);
                if (covers(theirs, m) || covers(m, theirs)) {
                    String narrower = m.length() >= theirs.length() ? m : theirs;
                    if (!out.contains(narrower)) out.add(narrower);
                }
            }
        }
        return out;
    }

    private static boolean covers(String prefix, String name) {
        if (!name.startsWith(prefix)) return false;
        if (name.length() == prefix.length()) return true;
        char c = name.charAt(prefix.length());
        return c == '.' || c == '$';
    }

    private static String join(List<String> l) {
        if (l.isEmpty()) return "<none>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    public String summary() {
        return "axAgentPresent=" + agentPresent
                + " tier1bStripRequested=" + stripRequested
                + " scopeOverlap=" + join(overlap)
                + " policy=" + policy
                + " tracerArmed=" + armTracer
                + (strippedDisabled ? " tier1bDisabledByTracer=true" : "");
    }
}
