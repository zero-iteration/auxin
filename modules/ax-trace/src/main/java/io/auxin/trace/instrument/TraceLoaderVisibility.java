package io.auxin.trace.instrument;

import io.auxin.trace.runtime.TraceRuntime;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Will the JVM resolve {@link TraceRuntime} on behalf of a class defined by THIS class loader?"
 *
 * <p>Every trace probe is an {@code INVOKESTATIC} naming {@code io/auxin/trace/runtime/*}. If
 * the loader does not delegate to the class path (OSGi, JBoss Modules, a JPMS custom layer, some
 * fat jars) that reference fails to resolve <b>inside application code</b>. Emitting it blindly
 * is not a missing trace, it is an availability bug.
 *
 * <h3>The rule, and the reason it is a copy and not a call</h3>
 * The logic is ax-agent's {@code LoaderVisibility} verbatim in substance — structural parent-chain
 * walk FIRST, then class identity — and the reasoning behind it (isolation suite F1: Felix's
 * {@code felix.bootdelegation.implicit=true} answers a stack-walking {@code Class.forName} from
 * inside {@code transform()} differently from a link-time resolution in a bundle, and the probe
 * also pre-seeds the loader's dictionary for the one name it asked about) is not re-derived here.
 * It is copied because this module must not depend on ax-agent, and because the question is about
 * a DIFFERENT class in a DIFFERENT loader: ax-agent's cache keyed on {@code ProbeHolder}'s
 * visibility would be the wrong answer for {@code TraceRuntime} if the two agents were ever
 * loaded by different loaders.
 *
 * <h3>There is no bridge</h3>
 * ax-agent reaches an agent-invisible loader through {@code java.lang.$Auxin}, which works for a
 * <i>constant</i> (the probe array) and explicitly does not work for a per-invocation
 * <i>call</i>: every {@code Object.equals}-based hop allocates an {@code Object[]}, which is why
 * {@code tier2NotBridgeable} exists. Every trace probe is a call. So a class whose loader cannot
 * see this module is <b>skipped and counted</b> ({@code agentNotVisible}), never broken. That is
 * a stated limitation in TRADE-OFFS.md, not an oversight.
 */
final class TraceLoaderVisibility {

    private static final String RUNTIME_BINARY = "io.auxin.trace.runtime.TraceRuntime";

    private static final ClassLoader AGENT_LOADER = TraceRuntime.class.getClassLoader();

    private static final int MAX_CHAIN_DEPTH = 256;

    private static final Map<ClassLoader, Boolean> CACHE =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

    static boolean visibleFrom(ClassLoader loader) {
        if (loader == null) return false;               // bootstrap never sees the agent jar
        if (loader == AGENT_LOADER) return true;

        Boolean cached = CACHE.get(loader);
        if (cached != null) return cached.booleanValue();

        boolean visible = prove(loader);
        CACHE.put(loader, Boolean.valueOf(visible));
        return visible;
    }

    private static boolean prove(ClassLoader loader) {
        try {
            // STRUCTURAL FIRST, and it short-circuits: for an isolated loader we never call
            // loadClass on it at all, so no dictionary pre-seeding and no re-entrant class load
            // into a foreign container from inside transform().
            if (!parentChainReaches(loader, AGENT_LOADER)) return false;
            return Class.forName(RUNTIME_BINARY, false, loader) == TraceRuntime.class;
        } catch (Throwable t) {
            return false;                               // unprovable == not visible == skip
        }
    }

    private static boolean parentChainReaches(ClassLoader loader, ClassLoader target) {
        if (target == null) return true;
        ClassLoader l = loader;
        for (int hops = 0; l != null && hops < MAX_CHAIN_DEPTH; hops++) {
            if (l == target) return true;
            l = l.getParent();
        }
        return false;
    }

    private TraceLoaderVisibility() { throw new AssertionError(); }
}
