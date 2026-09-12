package dev.auxin.staticscan.scan;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Excludes runtime-generated classes from the inventory.
 *
 * <p>WHY this is not merely tidiness: jacoco#655 demonstrated the failure empirically. A CGLIB proxy
 * or a lambda host class exists only inside one JVM run; it has no source, no stable name, and it
 * will never match a build-time manifest entry. Inventorying them inflates the manifest, inflates
 * the agent's per-class probe arrays without bound, and produces "dead" verdicts about classes that
 * were never written by anyone.
 *
 * <p>The proxy indirection itself does not need modelling: probes fire on the <em>real target
 * method</em> in every Spring proxy mode, so method identity survives proxying. Only the proxy class
 * is excluded, never its target.
 */
public final class GeneratedClassFilter {

    /** Literal markers a generator stamps into the class name. */
    private static final List<String> MARKERS = List.of(
            "$$EnhancerBySpringCGLIB$$",
            "$$EnhancerByCGLIB$$",
            "$$FastClassBySpringCGLIB$$",
            "$$SpringCGLIB$$",
            "$$Lambda",
            "GeneratedMethodAccessor",
            "GeneratedConstructorAccessor",
            "ByteBuddy$",
            "$MockitoMock$",
            "_$$_jvst");

    /** {@code java.lang.reflect.Proxy} names its output {@code $Proxy<n>}. */
    private static final Pattern JDK_PROXY = Pattern.compile(".*\\$Proxy\\d+$");

    /** {@code true} when this class was produced by a runtime generator and must not be inventoried. */
    public boolean isGenerated(String className) {
        for (String marker : MARKERS) {
            if (className.contains(marker)) {
                return true;
            }
        }
        return JDK_PROXY.matcher(className).matches();
    }
}
