package io.auxin.trace.config;

import io.auxin.agent.config.Options;
import io.auxin.trace.util.TLog;

import java.util.ArrayList;
import java.util.List;

/**
 * THE TRADE, TAKEN. {@code ax.trace.enabled=true} and Tier-1b's auto-strip cannot both hold for
 * the same classes — and since SCOPE-v3.1 the answer is no longer to refuse, it is to
 * <b>disable Tier-1b for the intersection of the two scopes, automatically and loudly.</b>
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
 * successfully — it matches tier-1 probe shapes and nothing the trace tier emits looks like one —
 * so the operator would see {@code classesStripped} rising, read it as "overhead is now zero",
 * and be wrong by however much the trace probes cost. <b>A true counter and a false conclusion</b>,
 * which is the hardest kind to notice and the exact signature of the nine
 * "reports success while doing nothing" bugs this project has already found.
 *
 * <h3>What changed when the tracer stopped being a second agent</h3>
 * <ol>
 *   <li><b>The default is now {@code disable-strip}, not {@code refuse}.</b> The owner took the
 *       trade explicitly: <i>"we can have one agent only, its fine if it adds overhead for some
 *       requests"</i>. Refusing to arm was the cautious answer to a question that has been
 *       answered.</li>
 *   <li><b>It is scoped, not global.</b> The old {@code disable-strip} set
 *       {@code ax.strip.enabled=false} for the whole JVM, which switched Tier-1b off for classes
 *       that were never traced. Now the decision is taken <b>per class</b>, at the moment
 *       Tier-1b considers one: a class in BOTH scopes keeps its probes, and a class in only
 *       {@code ax.include.packages} strips exactly as it always did. That is the entire point of
 *       having a scope — you pay where you asked to and nowhere else — and it is asserted by the
 *       smoke suite rather than assumed.</li>
 *   <li><b>The ordering hazard is gone, structurally.</b> The old mechanism set a system property
 *       from one premain and hoped the other premain had not read it yet; it then had to verify
 *       that reflectively three seconds after startup and escalate to
 *       {@code health.stripConflictUnresolved} when it had not taken. One premain reads one
 *       {@link Options} object, so there is no ordering left to get wrong and nothing to verify.
 *       {@code stripConflictUnresolved} stays on the wire for compatibility and is now
 *       structurally always 0.</li>
 * </ol>
 *
 * <h3>The three policies</h3>
 * <ul>
 *   <li>{@link TraceOptions#CONFLICT_DISABLE_STRIP} (DEFAULT) — arm the tracer; Tier-1b skips the
 *       intersection and strips everything else. One WARN at premain naming the scope, a field in
 *       the startup summary, and {@code tier1bDisabledByTrace} plus a class count on the wire.</li>
 *   <li>{@link TraceOptions#CONFLICT_REFUSE} — the pre-merge behaviour, still reachable: the
 *       tracer does not arm at all and ax-agent keeps every property it promised.</li>
 *   <li>{@link TraceOptions#CONFLICT_ALLOW} — arm both and let Tier-1b strip traced classes too.
 *       The operator has said explicitly that they accept a {@code classesStripped} count that
 *       does not mean what it says.</li>
 * </ul>
 *
 * <h3>Detection is scope-aware</h3>
 * There is no conflict at all when the two scopes do not overlap: tracing {@code com.acme.search}
 * while coverage covers {@code com.acme.billing} leaves billing's Tier-1b claim intact. Only the
 * intersection matters, and it is computed, printed, and enforced per class.
 */
public final class StripConflict {

    /** Is Tier-1b's auto-strip switched on at all for this JVM? */
    public final boolean stripRequested;

    /** The scope prefixes covered by BOTH {@code ax.include.packages} and the traced scope. */
    public final List<String> overlap;

    /** Both are on and their scopes intersect. */
    public final boolean conflicts;

    /** The policy in force: {@code disable-strip} / {@code refuse} / {@code allow}. */
    public final String policy;

    /** Does the trace tier arm at all? False only under {@code refuse} with a live conflict. */
    public final boolean armTracer;

    /**
     * Is Tier-1b's auto-strip being suppressed for the intersection? The field the startup
     * summary and the wire both carry, and the answer to "why is steady-state overhead not zero
     * on this JVM".
     */
    public final boolean tier1bDisabledByTrace;

    /** Null when nothing is suppressed. Consulted per class by Tier-1b. */
    private final TraceScope tracedScope;
    private final io.auxin.agent.config.Scope agentScope;

    private StripConflict(boolean stripRequested, List<String> overlap, String policy,
                          boolean armTracer, boolean tier1bDisabledByTrace,
                          TraceScope tracedScope, io.auxin.agent.config.Scope agentScope) {
        this.stripRequested = stripRequested;
        this.overlap = overlap;
        this.conflicts = stripRequested && !overlap.isEmpty();
        this.policy = policy;
        this.armTracer = armTracer;
        this.tier1bDisabledByTrace = tier1bDisabledByTrace;
        this.tracedScope = tracedScope;
        this.agentScope = agentScope;
    }

    /** No trace tier in this JVM: nothing is suppressed and nothing is said. */
    public static StripConflict inert() {
        return new StripConflict(false, new ArrayList<String>(), "none", false, false, null, null);
    }

    /**
     * Decides the policy once, at premain, from the one {@link Options} object. Prints the WARN
     * when there is something to warn about; says nothing when the scopes do not overlap.
     */
    public static StripConflict detect(Options o) {
        // Tier-1b only auto-strips when the agent, tier-1 and the strip are all on. Absence of
        // ax.strip.enabled means the strip IS on (it defaults to true), so reading the default
        // as "off" would turn this whole check into a no-op in exactly the default configuration.
        final boolean strip = o.enabled && o.tier1Enabled && o.stripEnabled;
        final List<String> overlap = overlap(o);

        if (!strip || overlap.isEmpty()) {
            return new StripConflict(strip, overlap, "none", true, false, null, null);
        }

        if (TraceOptions.CONFLICT_ALLOW.equals(o.trace.stripConflict)) {
            TLog.warn(banner(overlap)
                    + "\n  ax.trace.strip.conflict=allow: BOTH are armed. Tier-1b will report "
                    + "successful strips for these classes and their steady-state overhead will "
                    + "NOT be zero -- the trace probes remain. This was asked for explicitly and "
                    + "is reported in every trace document's health block.");
            return new StripConflict(true, overlap, TraceOptions.CONFLICT_ALLOW,
                    true, false, null, null);
        }
        if (TraceOptions.CONFLICT_REFUSE.equals(o.trace.stripConflict)) {
            TLog.warn(banner(overlap)
                    + "\n  ax.trace.strip.conflict=refuse: THE TRACE TIER IS NOT ARMED. Nothing "
                    + "is instrumented for tracing and no request can be traced. Tier-1b keeps "
                    + "every property it promised."
                    + "\n  This is no longer the default (SCOPE-v3.1). To trace, pick one:"
                    + "\n    - ax.trace.strip.conflict=disable-strip  (the default: Tier-1b skips"
                    + " the overlap and strips everything else)"
                    + "\n    - ax.trace.strip.conflict=allow          (keep both, accept a"
                    + " classesStripped count that does not mean zero overhead)"
                    + "\n    - ax.strip.enabled=false                 (give up Tier-1b entirely)"
                    + "\n    - narrow ax.trace.include.packages so it does not overlap"
                    + " ax.include.packages");
            return new StripConflict(true, overlap, TraceOptions.CONFLICT_REFUSE,
                    false, false, null, null);
        }

        // THE DEFAULT. Taken, scoped, and said out loud.
        TLog.warn(banner(overlap)
                + "\n  ax.trace.strip.conflict=disable-strip (the default since SCOPE-v3.1): "
                + "TIER-1b AUTO-STRIP IS DISABLED FOR " + join(overlap) + " and for nothing else."
                + "\n  Classes in that intersection keep their coverage probes for the life of "
                + "this JVM, so their steady-state overhead is NOT zero and classesStripped will "
                + "never count them. Classes covered by ax.include.packages but OUTSIDE the "
                + "traced scope strip normally -- that is what the scope is for."
                + "\n  Coverage, tier-2 and the call-edge tier are unaffected; only the "
                + "de-instrumentation step is. Reported as agentHealth.tier1bDisabledByTrace=true "
                + "with the class count, so the server and UI can render WHY."
                + "\n  Turn it off with ax.trace.enabled=false, or narrow "
                + "ax.trace.include.packages.");
        return new StripConflict(true, overlap, TraceOptions.CONFLICT_DISABLE_STRIP,
                true, true, o.trace.scope, o.scope);
    }

    /**
     * Tier-1b's per-class question: must this class keep its probes because it is traced?
     *
     * <p>Asked of the two live {@code Scope} objects rather than of the printed prefix list, so
     * the answer is the same segment-boundary match the transformer used. A class is in the
     * intersection only if BOTH scopes admit it — which is why a class outside
     * {@code ax.trace.include.packages} still strips.
     *
     * @param dottedClassName e.g. {@code com.acme.search.SearchService}
     */
    public boolean blocksStrip(String dottedClassName) {
        if (!tier1bDisabledByTrace || tracedScope == null || agentScope == null) return false;
        final String internal = dottedClassName.replace('.', '/');
        return tracedScope.included(internal) && agentScope.included(internal);
    }

    private static String banner(List<String> overlap) {
        return "TIER-1b / TRACE OVERLAP."
                + "\n  ax.trace.enabled=true and Tier-1b's auto-strip both cover: " + join(overlap)
                + "\n  A TRACE PROBE CAN NEVER BE STRIPPED -- you cannot know in advance which "
                + "request will be traced -- so Tier-1b's \"steady state reaches zero\" is FALSE "
                + "for these classes while the trace tier is armed. Tier-1b would still report "
                + "successful strips, which is a true counter and a false conclusion.";
    }

    /**
     * The intersection of the two scopes, as package prefixes. Either containing the other counts
     * — {@code com.acme} covers {@code com.acme.search} and vice versa — and the NARROWER of the
     * pair is what is printed, because that is the set of classes actually affected.
     */
    static List<String> overlap(Options o) {
        List<String> out = new ArrayList<String>();
        if (!o.traceEnabled()) return out;
        List<String> mine = o.trace.scope.includeDotted();
        List<String> theirs = o.scope.includeDotted();
        for (int i = 0; i < theirs.size(); i++) {
            for (int k = 0; k < mine.size(); k++) {
                String a = theirs.get(i);
                String b = mine.get(k);
                if (covers(a, b) || covers(b, a)) {
                    String narrower = b.length() >= a.length() ? b : a;
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

    /** The startup-summary fragment. One line, read by an operator, not by a parser. */
    public String summary() {
        return "tier1bStripRequested=" + stripRequested
                + " scopeOverlap=" + join(overlap)
                + " policy=" + policy
                + " traceArmed=" + armTracer
                + " tier1bDisabledByTrace=" + tier1bDisabledByTrace;
    }
}
