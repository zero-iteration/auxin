package io.auxin.agent.instrument;

import io.auxin.agent.runtime.ProbeHolder;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Will the JVM resolve {@link ProbeHolder} on behalf of a class defined by THIS class loader?"
 *
 * <p>The condy bootstrap handle, the pre-55 {@code <clinit>} call and every tier-2 call site put
 * an {@code io.auxin.agent.runtime.*} symbolic reference into the instrumented class's
 * constant pool. If the loader does not delegate to the system class path (OSGi, JBoss Modules, a
 * JPMS custom layer, some fat jars) that reference fails to resolve <b>inside application code</b>.
 * Emitting it blindly is not a coverage bug, it is an availability bug.
 *
 * <h3>Why {@code Class.forName} alone is the WRONG question (isolation suite F1)</h3>
 * A probe of the form {@code Class.forName(HOLDER, false, loader)} runs on the <b>agent's</b> call
 * stack, inside {@code transform()}. Some containers decide delegation by <b>walking that stack</b>.
 * Apache Felix ships {@code felix.bootdelegation.implicit=true} by default: on a failed bundle
 * class load it inspects the stack and, if the first non-framework class on it was not loaded by a
 * bundle, it silently delegates to that class's loader. So the same loader gives two different
 * answers for the same name:
 * <pre>
 *   asked by the agent (inside transform)      -&gt; FOUND    (delegated to the agent's loader)
 *   asked by the instrumented class at link    -&gt; ClassNotFoundException: ... not found by bundle
 * </pre>
 * The measured consequence was a {@code ClassNotFoundException} for {@code Tier2Runtime} thrown
 * out of {@code Service.alpha(int)} — application code — on JDK 11, 17 and 21, in Felix's
 * out-of-the-box configuration. Worse, the probe also <i>pre-seeds</i> the loader's dictionary for
 * the single name it asked about, so tier 1 appeared to work while every tier-2 boundary method in
 * every bundle became an outage on its first call.
 *
 * <h3>The rule</h3>
 * Visibility must be a property of the class loader <b>graph</b>, not of whoever happens to be on
 * the stack. Both of these must hold:
 * <ol>
 *   <li><b>structural</b> — the loader's parent chain actually reaches the loader that defined
 *       {@code ProbeHolder}. Parent delegation is the only delegation the JVM itself performs when
 *       it resolves a constant-pool entry on the instrumented class's behalf, and
 *       {@link ClassLoader#getParent()} is a pure graph query: it loads nothing, calls no
 *       container hook and cannot see the call stack; and</li>
 *   <li><b>identity</b> — the class that loader resolves is the SAME {@link Class} object the
 *       agent itself loaded. A child-first loader carrying a copy of the agent jar resolves the
 *       name successfully and returns a <i>different</i> class, whose {@code ProbeHolder} nothing
 *       ever flushes.</li>
 * </ol>
 * Neither condition subsumes the other. The structural walk cannot see a child-first loader's
 * shadowing rules (that is what the identity test is for); the identity test cannot see
 * stack-dependent delegation (that is what the walk is for). Measured across the four Felix
 * configurations in the isolation suite:
 * <pre>
 *   implicit            forName=FOUND(agent's own)  chain=false  -&gt; NOT visible  (was: visible, BUG)
 *   strict              forName=CNFE                chain=false  -&gt; NOT visible
 *   bootdelegation      forName=CNFE                chain=false  -&gt; NOT visible
 *   bootdelegation-app  forName=FOUND(agent's own)  chain=true   -&gt; visible
 * </pre>
 * A loader that would in fact have delegated to the agent without having it in its parent chain is
 * demoted to the {@code java.lang.$Auxin} bridge, which costs coverage fidelity (see F2) but
 * cannot fail. Failing toward "no data" is always allowed; failing toward "throws inside a
 * business method" never is.
 *
 * <p><b>Order matters.</b> The structural walk runs FIRST and short-circuits. For an isolated
 * loader the agent therefore never calls {@code loadClass} on it at all — no dictionary
 * pre-seeding, and no re-entrant class load into a foreign container from inside
 * {@code transform()}.
 *
 * <p>Asked once per loader, cached weakly; the answer is stable for a loader's lifetime because
 * both inputs (its parent chain and which class it resolves for that name) are.
 *
 * <p>Re-entrancy, for the one call that remains: {@code Class.forName} here runs inside a
 * {@code loadClass} call for a DIFFERENT name, so the per-name lock a parallel-capable loader
 * takes is a different lock, and a non-parallel-capable loader's single lock is already held by
 * this same thread (re-entrant). The class we ask for is already loaded by the agent's own loader,
 * which we have just proved is an ancestor, so a delegating loader answers from its parent's cache
 * without doing any I/O.
 */
final class LoaderVisibility {

    private static final String HOLDER_BINARY = "io.auxin.agent.runtime.ProbeHolder";

    /** Whoever loaded the agent runtime; normally the system class loader (-javaagent appends). */
    private static final ClassLoader AGENT_LOADER = ProbeHolder.class.getClassLoader();

    /**
     * A parent chain deeper than this is either pathological or cyclic (nothing forbids a loader
     * from returning a cycle from {@code getParent()}). Bound the walk and answer "not provable".
     */
    private static final int MAX_CHAIN_DEPTH = 256;

    private static final Map<ClassLoader, Boolean> CACHE =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

    static boolean agentVisibleFrom(ClassLoader loader) {
        // The bootstrap loader never sees the agent jar (we refuse to use
        // appendToBootstrapClassLoaderSearch — see BootstrapBridge).
        if (loader == null) return false;
        if (loader == AGENT_LOADER) return true;

        Boolean cached = CACHE.get(loader);
        if (cached != null) return cached.booleanValue();

        boolean visible = prove(loader);
        CACHE.put(loader, Boolean.valueOf(visible));
        return visible;
    }

    /** Both conditions, cheapest and least invasive first. Never throws. */
    private static boolean prove(ClassLoader loader) {
        try {
            if (!parentChainReaches(loader, AGENT_LOADER)) return false;
            return Class.forName(HOLDER_BINARY, false, loader) == ProbeHolder.class;
        } catch (Throwable t) {
            // Includes SecurityException from getParent() and anything a hostile or broken
            // loader throws. Unprovable == not visible == use the bridge or skip.
            return false;
        }
    }

    /**
     * Does {@code loader} delegate to {@code target} through nothing but parent links? This is the
     * only delegation the JVM performs itself, and it is the same in every container and on every
     * thread, which is precisely the property a stack-walking probe lacks.
     */
    private static boolean parentChainReaches(ClassLoader loader, ClassLoader target) {
        // target == null would mean ProbeHolder was defined by the bootstrap loader. Every chain
        // ends there by construction (getParent() == null IS the bootstrap loader), so the
        // structural condition is trivially satisfied and only identity decides.
        if (target == null) return true;
        ClassLoader l = loader;
        for (int hops = 0; l != null && hops < MAX_CHAIN_DEPTH; hops++) {
            if (l == target) return true;
            l = l.getParent();
        }
        return false;
    }

    private LoaderVisibility() { throw new AssertionError(); }
}
