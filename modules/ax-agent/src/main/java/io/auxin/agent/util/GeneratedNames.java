package io.auxin.agent.util;

/**
 * Runtime-generated class names, from CONTRACTS section 1: <i>"Runtime-generated classes are
 * NEVER inventoried or probed."</i>
 *
 * <p>Lives in util because both the transformer (which must not probe them) and the manifest
 * generator (which must not inventory them) need the identical rule. jacoco#655: these grow
 * agent memory without bound — one 188MB exec file consumed over 2GB.
 */
public final class GeneratedNames {

    private static final String[] MARKERS = {
            "$$EnhancerBySpringCGLIB$$",
            "$$EnhancerByCGLIB$$",
            "$$EnhancerBy",
            "$$FastClassBySpringCGLIB$$",
            "$$FastClassBy",
            "$$Lambda",
            "$Proxy",
            "GeneratedMethodAccessor",
            "GeneratedConstructorAccessor",
            "GeneratedSerializationConstructorAccessor",
            "ByteBuddy$",
            "$MockitoMock$",
            "_$$_jvst",
            "CGLIB$$",
            "$HibernateProxy",
            "_jsp",
    };

    /** @param name internal or dotted form; the markers contain no separators. */
    public static boolean isGenerated(String name) {
        for (int i = 0; i < MARKERS.length; i++) {
            if (name.indexOf(MARKERS[i]) >= 0) return true;
        }
        return false;
    }

    private GeneratedNames() { throw new AssertionError(); }
}
