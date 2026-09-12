package io.auxin.agent.instrument;

import org.objectweb.asm.ClassReader;

/**
 * Which classes must never be instrumented, and how that interacts with the operator's
 * {@code ax.include.packages} scope.
 *
 * <p><b>This class replaces the flat {@code IgnoreList} (G5-BUG-1.)</b> That list was a single
 * undifferentiated array matched with {@code startsWith} and consulted <i>before</i>
 * {@code Scope.included()}, so it could — and did — veto the operator's own application. On the
 * G5 demo app it matched {@code io/auxin/} against {@code io.auxin.demo.*} and the
 * agent instrumented <b>zero classes while reporting a clean run</b>. Prefixes like
 * {@code datadog/}, {@code com/newrelic/} and {@code com/dynatrace/} carry exactly the same
 * hazard for a customer whose own packages start with those strings.
 *
 * <p>So there are two tiers, and the difference is precedence:
 *
 * <h3>HARD — never instrumented, whatever the scope says</h3>
 * The JDK ({@code java/}, {@code jdk/}, {@code sun/}, {@code com/sun/}, {@code javax/}) and
 * <b>our own runtime</b>. Instrumenting either is unbounded recursion or a {@code LinkageError},
 * not a coverage trade-off. Our own runtime is identified by the <i>actual</i> package this class
 * was loaded from ({@link #agentRuntime()}) and the <i>actual</i> package our shaded ASM was
 * relocated to ({@link #asmRuntime()}) — computed, not guessed, and therefore narrow: it claims
 * {@code io.auxin.agent.*} and {@code io.auxin.shaded.asm.*} and nothing else in
 * {@code io.auxin.*} user space.
 *
 * <h3>SOFT — other agents' runtimes; an at-least-as-specific include scope wins</h3>
 * Sources: OTel's {@code GlobalIgnoredTypesConfigurer}, plus {@code org.jacoco.} and
 * {@code org.apache.skywalking.} which OTel does NOT ignore (VALIDATION A6/C36). These exist so
 * that a <i>broad</i> scope ({@code ax.include.packages=com}) does not drag another agent's
 * classes in — instrumenting somebody else's agent is how you get "class redefinition failed:
 * attempted to delete a method". They do <b>not</b> exist to veto customer code, so precedence is
 * settled by specificity: if the include prefix that matched is at least as long as the soft
 * prefix, the operator named it deliberately and the scope wins. Either way the outcome is
 * counted and logged — never silent (VALIDATION C37).
 */
public final class IgnoreRules {

    /** The agent's own root package in internal form, e.g. {@code io/auxin/agent/}. */
    private static final String AGENT_RUNTIME = agentRuntimePrefix();

    /** Our shaded ASM's root package, e.g. {@code io/auxin/shaded/asm/}. */
    private static final String ASM_RUNTIME = asmRuntimePrefix();

    /** Vetoed regardless of {@code ax.include.packages}. Order: cheapest/commonest first. */
    private static final String[] HARD = {
            "java/",
            "jdk/",
            "sun/",
            "com/sun/",
            "javax/",
            AGENT_RUNTIME,
            ASM_RUNTIME,
    };

    /** Other agents' runtimes. Vetoed only when the include scope is less specific. */
    private static final String[] FOREIGN = {
            "io/opentelemetry/",
            "net/bytebuddy/",
            "org/jacoco/",
            "datadog/",
            "com/datadog/",
            "com/newrelic/",
            "com/dynatrace/",
            "com/appdynamics/",
            "org/apache/skywalking/",
            "org/aspectj/",
    };

    /**
     * @return the HARD prefix that vetoes this class, or {@code null}. A non-null answer is
     *         final: no scope, however specific, overrides it.
     */
    public static String hardVeto(String internalName) {
        for (int i = 0; i < HARD.length; i++) {
            if (internalName.startsWith(HARD[i])) return HARD[i];
        }
        return null;
    }

    /**
     * @return the SOFT prefix that would veto this class, or {@code null}. The caller must
     *         compare it against the include prefix that matched — see
     *         {@link #scopeWins(String, String)}.
     */
    public static String foreignVeto(String internalName) {
        for (int i = 0; i < FOREIGN.length; i++) {
            if (internalName.startsWith(FOREIGN[i])) return FOREIGN[i];
        }
        return null;
    }

    /**
     * Precedence between an explicit include prefix and a soft veto prefix: the more specific
     * declaration wins. {@code include=io/auxin/demo/} beats {@code io/auxin/};
     * {@code include=com/} loses to {@code com/newrelic/}.
     *
     * @param includePrefix the include prefix that matched, never {@code null} here
     */
    public static boolean scopeWins(String includePrefix, String foreignPrefix) {
        if (includePrefix == null) return false;
        // Scope prefixes carry no trailing slash ({@code datadog}) and ignore prefixes do
        // ({@code datadog/}), so compare them normalised — otherwise naming a vendor root
        // exactly would read as one character LESS specific than the veto and lose to it,
        // which is G5-BUG-1 again with a different prefix.
        return trimmedLength(includePrefix) >= trimmedLength(foreignPrefix);
    }

    private static int trimmedLength(String prefix) {
        int n = prefix.length();
        return n > 0 && prefix.charAt(n - 1) == '/' ? n - 1 : n;
    }

    /**
     * Is this hard prefix one of ours?
     *
     * <p>The caller needs to know because <b>nothing may touch an agent class while the JVM is
     * in the middle of defining one</b>: resolving {@code Health} or {@code ProbeHolder} from
     * inside the transform of an agent class is a recursive class load. Refusing to instrument
     * our own runtime also needs no announcement per class — an include scope that overlaps it is
     * reported once, at premain, where there is nothing to recurse into.
     */
    public static boolean isOwnRuntime(String hardPrefix) {
        return AGENT_RUNTIME.equals(hardPrefix) || ASM_RUNTIME.equals(hardPrefix);
    }

    /** Runtime-generated classes: never probed (CONTRACTS section 1, A14 defect 3). */
    public static boolean generated(String internalName) {
        return io.auxin.agent.util.GeneratedNames.isGenerated(internalName);
    }

    /** The agent's own package prefix, for the startup log. */
    public static String agentRuntime() { return AGENT_RUNTIME; }

    /** The shaded-ASM package prefix, for the startup log. */
    public static String asmRuntime() { return ASM_RUNTIME; }

    // ---- deriving our own identity, so a relocated build says the truth about itself ----

    /**
     * This class lives in {@code <agent root>.instrument}, so the agent root is two segments up
     * from the class name. Derived rather than hard-coded: a build that relocates
     * {@code io.auxin.agent} must veto the relocated names, and must NOT keep vetoing a
     * customer's {@code io.auxin.*}.
     */
    private static String agentRuntimePrefix() {
        String p = parentPackage(packagePrefix(IgnoreRules.class));
        return sane(p) ? p : "io/auxin/agent/";
    }

    private static String asmRuntimePrefix() {
        String p = packagePrefix(ClassReader.class);
        return sane(p) ? p : "io/auxin/shaded/asm/";
    }

    /** {@code io.auxin.agent.instrument.IgnoreRules} -> {@code io/auxin/agent/instrument/}. */
    private static String packagePrefix(Class<?> c) {
        try {
            String n = c.getName().replace('.', '/');
            int slash = n.lastIndexOf('/');
            return slash <= 0 ? "" : n.substring(0, slash + 1);
        } catch (Throwable t) {
            return "";
        }
    }

    /** {@code a/b/c/} -> {@code a/b/}. */
    private static String parentPackage(String prefix) {
        if (prefix.length() < 2) return "";
        int slash = prefix.lastIndexOf('/', prefix.length() - 2);
        return slash <= 0 ? "" : prefix.substring(0, slash + 1);
    }

    /**
     * A derived prefix must name at least two package segments. Anything shorter ({@code io/},
     * or the empty string from a default package) would be broad enough to reintroduce
     * G5-BUG-1, so it is rejected in favour of the literal fallback.
     */
    private static boolean sane(String prefix) {
        if (prefix == null || prefix.length() == 0) return false;
        int slashes = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) == '/') slashes++;
        }
        return slashes >= 2;
    }

    private IgnoreRules() { throw new AssertionError(); }
}
