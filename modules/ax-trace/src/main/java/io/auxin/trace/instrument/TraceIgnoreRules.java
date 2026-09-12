package io.auxin.trace.instrument;

/**
 * Never-instrumentable prefixes. Copied in policy from ax-agent's {@code IgnoreRules}, which in
 * turn copies OTel's {@code GlobalIgnoredTypesConfigurer} — this module keeps its own copy
 * rather than depending on ax-agent, because the whole point of the separation is that neither
 * module can change the other's behaviour.
 *
 * <p>Two tiers, and the precedence rule from G5-BUG-1 applies unchanged:
 * <b>hard veto &gt; explicit scope &gt; soft veto</b>. A hard veto is a class that cannot be
 * instrumented without breaking the JVM or this agent; a soft veto is another agent's runtime,
 * which an operator's more specific scope may override.
 */
public final class TraceIgnoreRules {

    private static final String OWN_RUNTIME = "io/auxin/trace/";
    private static final String OWN_ASM = "io/auxin/trace/shaded/asm/";

    /** Cannot be instrumented, ever. Not counted as a skip on the common startup path. */
    private static final String[] HARD = {
            "java/", "javax/crypto/", "jdk/", "sun/", "com/sun/", "org/w3c/dom/", "org/xml/sax/",
            "org/ietf/jgss/",
            OWN_RUNTIME, OWN_ASM,
            // ax-agent's own runtime: instrumenting the other agent's probe path would be a
            // recursive class load at best and a LinkageError at worst.
            "io/auxin/agent/", "io/auxin/shaded/asm/",
            "org/objectweb/asm/",
    };

    /** Another agent's or profiler's runtime. Overridable by a more specific include prefix. */
    private static final String[] SOFT = {
            "org/jacoco/", "org/apache/skywalking/", "io/opentelemetry/javaagent/",
            "datadog/", "com/datadoghq/", "com/newrelic/", "com/dynatrace/", "com/appdynamics/",
            "one/profiler/", "io/micrometer/", "net/bytebuddy/",
    };

    /** Runtime-generated classes. They are never in any manifest and grow without bound. */
    private static final String[] GENERATED = {
            "$$EnhancerBySpringCGLIB$$", "$$EnhancerByCGLIB$$", "$$FastClassBySpringCGLIB$$",
            "$$Lambda$", "GeneratedMethodAccessor", "GeneratedConstructorAccessor",
            "ByteBuddy$", "$MockitoMock$", "_$$_jvst", "$HibernateProxy$",
    };

    public static String hardVeto(String internalName) {
        for (int i = 0; i < HARD.length; i++) {
            if (internalName.startsWith(HARD[i])) return HARD[i];
        }
        if (internalName.startsWith("java/lang/")) return "java/";
        return null;
    }

    public static String foreignVeto(String internalName) {
        for (int i = 0; i < SOFT.length; i++) {
            if (internalName.startsWith(SOFT[i])) return SOFT[i];
        }
        return null;
    }

    /**
     * Runtime-generated. Excluded by NAME, and deliberately not by a regex over
     * {@code $Proxy\d+}: a lambda body on an application class is
     * {@code Owner$$Lambda$12}, which this catches, while the LAMBDA'S TARGET METHOD is a
     * synthetic {@code lambda$foo$0} on the owner class and IS instrumented — that is how the
     * {@code parallelStream} window sees anything at all.
     */
    public static boolean generated(String internalName) {
        for (int i = 0; i < GENERATED.length; i++) {
            if (internalName.contains(GENERATED[i])) return true;
        }
        return isJdkProxy(internalName);
    }

    private static boolean isJdkProxy(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String simple = slash < 0 ? internalName : internalName.substring(slash + 1);
        if (!simple.startsWith("$Proxy")) return false;
        for (int i = 6; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return simple.length() > 6;
    }

    public static boolean isOwnRuntime(String prefix) {
        return OWN_RUNTIME.equals(prefix) || OWN_ASM.equals(prefix);
    }

    /** Does an operator's include prefix beat a soft veto? Only if it is at least as specific. */
    public static boolean scopeWins(String matchedIncludePrefix, String vetoPrefix) {
        return matchedIncludePrefix != null
                && matchedIncludePrefix.length() >= vetoPrefix.length();
    }

    public static String ownRuntime() { return OWN_RUNTIME.replace('/', '.'); }

    private TraceIgnoreRules() { throw new AssertionError(); }
}
