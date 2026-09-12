package io.auxin.agent.runtime;

import io.auxin.agent.health.Health;
import io.auxin.agent.util.Log;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/**
 * The {@code java.lang.$Auxin} bootstrap bridge — JaCoCo's recipe, verbatim in intent.
 *
 * <p><b>The problem.</b> {@link ProbeHolder} is a plain agent-jar class on the system class path.
 * Instrumented code can only reach it where the application's loader delegates to the system
 * loader. OSGi bundle loaders, JBoss Modules, JPMS named modules and some Spring Boot fat-jar
 * loaders do not, so the probe's symbolic reference would fail to resolve <i>inside application
 * code</i> — a {@code NoClassDefFoundError} or {@code BootstrapMethodError} on a business method,
 * which is an outage, not a missing measurement.
 *
 * <p><b>The fix.</b> One synthetic class in {@code java.lang}, holding exactly one member:
 * <pre>public static Object data;</pre>
 * {@code java.*} is universally boot-delegated — every class loader arrangement in existence,
 * including OSGi's, must delegate it to the bootstrap loader — so a probe that references only
 * {@code java.lang.$Auxin.data}, {@code java.lang.Object} and {@code Object.equals} resolves
 * everywhere. The probe array is delivered through an overridden {@code equals(Object)} which
 * stuffs it into {@code args[0]}: that is the whole trick, and it is why no agent type ever has to
 * appear in the instrumented class's constant pool.
 *
 * <p><b>How it is installed</b> (JDK 9+ only):
 * <ol>
 *   <li>{@code Instrumentation.redefineModule} opens {@code java.lang} to the agent's own unnamed
 *       module ONLY — not to everybody, not for ever after.</li>
 *   <li>{@code MethodHandles.privateLookupIn(Object.class, lookup()).defineClass(bytes)} defines
 *       the class in {@code java.lang}, and therefore in the bootstrap loader.</li>
 * </ol>
 *
 * <p><b>{@code appendToBootstrapClassLoaderSearch} is deliberately NOT used.</b> Its own javadoc
 * warns that a symbolic reference which has already failed to resolve stays failed for the life of
 * the JVM, and it does not cover resource lookup. It converts a race into a permanent, silent
 * hole in coverage.
 *
 * <p><b>JDK 8</b> has neither {@code privateLookupIn} nor modules: the bridge is unavailable, the
 * agent logs once and falls back to {@link ProbeHolder}, which is correct on every JDK-8 host
 * whose loaders delegate.
 *
 * <p>Everything here is reflective because the agent compiles to Java 8 bytecode.
 */
public final class BootstrapBridge {

    public static final String BRIDGE_BINARY = "java.lang.$Auxin";
    public static final String BRIDGE_INTERNAL = "java/lang/$Auxin";
    public static final String DATA_NAME = "data";
    public static final String DATA_DESC = "Ljava/lang/Object;";

    private static volatile boolean installed;
    private static volatile String status = "notAttempted";

    /** The exact object we parked in {@code $Auxin.data}; the yardstick for {@link #intact()}. */
    private static volatile Data ours;
    /** Cached so {@link #intact()} costs one field read, not a reflective lookup, per class. */
    private static volatile Field dataField;
    private static volatile boolean tamperWarned;

    /** True when {@code java.lang.$Auxin} exists and we defined it. */
    public static boolean installed() { return installed; }

    /** Why the bridge is (not) available, for the log line and for tests. */
    public static String status() { return status; }

    /**
     * Is {@code java.lang.$Auxin.data} still the object we put there? (isolation suite F3)
     *
     * <p>The field has to be {@code public static} for the {@code Object.equals} trick to work
     * from an arbitrary loader, which makes it a JVM-global mutable hook: anything in the process
     * can overwrite it, and a replacement object whose {@code equals} does not fill in
     * {@code args[0]} leaves a {@code String} where the caller expects a {@code boolean[]}.
     * JaCoCo's {@code java.lang.$JaCoCo} has the identical exposure.
     *
     * <p>This check is the FIRST of two defences, and it is the weaker one: a public static field
     * is writable by definition, and the write can land at any moment, including after this check
     * has passed. What it buys is that a tamper which has <i>already</i> happened (the realistic
     * case: a second agent's {@code premain}, which runs before any application class is loaded)
     * is detected and the class is skipped and counted instead of instrumented. Cost: one
     * cached-{@link Field} read per class that takes the bridge path, at transform time; nothing
     * on the hot path.
     *
     * <p>The second defence is the one that actually closes F3, and it only exists in the
     * self-BSM shape (F2): the synthetic {@code $axInit} bootstrap method this agent authors ends
     * in {@code INSTANCEOF [Z} and degrades to a throwaway {@code new boolean[n]} when the hook
     * has been taken, so a write that lands after this check costs coverage for one class and
     * cannot reach application code at all. The pre-F2 {@code <clinit>} prologue could not do
     * that: inserting a branch into somebody else's {@code <clinit>} needs a merge frame, which
     * is where the VerifyError risk lives.
     *
     * @return true when the bridge can be relied on right now.
     */
    public static boolean intact() {
        if (!installed) return false;
        Field f = dataField;
        Data mine = ours;
        if (f == null || mine == null) return false;
        try {
            if (f.get(null) == mine) return true;
        } catch (Throwable t) {
            // A read of a public static field of a public java.lang class should not throw. If it
            // does, we cannot vouch for the bridge, which is the same answer as a tamper.
            warnTamper("unreadable: " + t.getClass().getName());
            return false;
        }
        warnTamper("overwritten by a third party");
        return false;
    }

    private static void warnTamper(String what) {
        if (tamperWarned) return;
        tamperWarned = true;
        Log.warn(BRIDGE_BINARY + "." + DATA_NAME + " " + what
                + ": the bridge can no longer be trusted. Classes whose loader cannot see the "
                + "agent jar will be skipped and counted (" + Health.SKIP_BRIDGE_TAMPERED
                + "), not instrumented.");
    }

    /**
     * Idempotent. Never throws: a JVM without the bridge is a JVM with less coverage, never a
     * JVM that fails to start.
     *
     * @return true when {@code java.lang.$Auxin} is defined and carries the runtime object.
     */
    public static synchronized boolean install(Instrumentation inst) {
        if (installed) return true;
        try {
            if (inst == null) return fail("noInstrumentation", null);
            if (!hasPrivateLookupIn()) return fail("jdk8NoPrivateLookupIn", null);
            if (alreadyDefined()) return fail("alreadyDefinedByAnotherAgent", null);
            String opened = openJavaLang(inst);
            if (opened != null) return fail(opened, null);

            Class<?> bridge = define(generate());
            Field data = bridge.getField(DATA_NAME);
            Data mine = new Data();
            data.set(null, mine);
            ours = mine;
            dataField = data;

            installed = true;
            status = "installed";
            Log.info("bootstrap bridge installed: " + BRIDGE_BINARY
                    + " (instrumented classes in agent-invisible loaders reference java.lang only)");
            return true;
        } catch (Throwable t) {
            return fail("failed:" + t.getClass().getSimpleName(), t);
        }
    }

    private static boolean fail(String reason, Throwable t) {
        status = reason;
        installed = false;
        // counted once per JVM: a coverage hole in OSGi/JPMS hosts must be visible on the wire,
        // not inferred from the absence of data (which is indistinguishable from dead code).
        Health.skip(Health.SKIP_BRIDGE_UNAVAILABLE);
        Log.warn("bootstrap bridge unavailable (" + reason + "): falling back to ProbeHolder. "
                + "Classes whose loader cannot see the agent jar will be skipped, not instrumented.", t);
        return false;
    }

    // ---------------- steps ----------------

    private static boolean hasPrivateLookupIn() {
        try {
            MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A second auxin agent (or anything else) already owns the name: do not fight over it. */
    private static boolean alreadyDefined() {
        try {
            Class.forName(BRIDGE_BINARY, false, null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return null on success, otherwise the failure reason. */
    private static String openJavaLang(Instrumentation inst) throws Exception {
        Method getModule;
        try {
            getModule = Class.class.getMethod("getModule");
        } catch (NoSuchMethodException e) {
            return "jdk8NoModules";
        }
        Class<?> moduleClass = Class.forName("java.lang.Module");
        Object javaBase = getModule.invoke(Object.class);
        Object agentModule = getModule.invoke(BootstrapBridge.class);

        Method isModifiable = Instrumentation.class.getMethod("isModifiableModule", moduleClass);
        if (!Boolean.TRUE.equals(isModifiable.invoke(inst, javaBase))) return "javaBaseNotModifiable";

        Method redefineModule = Instrumentation.class.getMethod("redefineModule",
                moduleClass, java.util.Set.class, java.util.Map.class,
                java.util.Map.class, java.util.Set.class, java.util.Map.class);
        // extraOpens ONLY, java.lang ONLY, to the agent's unnamed module ONLY.
        redefineModule.invoke(inst, javaBase,
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.singletonMap("java.lang", Collections.singleton(agentModule)),
                Collections.emptySet(),
                Collections.emptyMap());
        return null;
    }

    private static Class<?> define(byte[] bytes) throws Exception {
        Method privateLookupIn = MethodHandles.class.getMethod(
                "privateLookupIn", Class.class, MethodHandles.Lookup.class);
        Object lookup = privateLookupIn.invoke(null, Object.class, MethodHandles.lookup());
        Method defineClass = MethodHandles.Lookup.class.getMethod("defineClass", byte[].class);
        return (Class<?>) defineClass.invoke(lookup, (Object) bytes);
    }

    /** Exactly one field. No methods, no constructor, no {@code <clinit>}, nothing to go wrong. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                BRIDGE_INTERNAL, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                DATA_NAME, DATA_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The object parked in {@code java.lang.$Auxin.data}. Instrumented classes call
     * {@code Object.equals(Object)} on it — a {@code java.lang} method on a {@code java.lang}
     * type — and this override answers with the probe array, in place, in {@code args[0]}.
     *
     * <p>Called exactly once per instrumented class. Two argument shapes exist:
     * <table>
     *   <tr><th>shape</th><th>array</th><th>called from</th></tr>
     *   <tr><td>self-BSM (F2, default)</td>
     *       <td>{@code {className, probeCount, lookup.lookupClass()}}</td>
     *       <td>the class's synthetic {@code $axInit} condy bootstrap method</td></tr>
     *   <tr><td>field ({@code ax.bridge.shape=field})</td>
     *       <td>{@code {className, probeCount}}</td>
     *       <td>the class's {@code <clinit>} prologue</td></tr>
     * </table>
     * The third element is the whole point of F2: it is the only way a bridged class can hand
     * the agent a {@link Class} handle, and without one Tier-1b can never de-instrument it.
     *
     * <p>This runs on an application thread during class initialisation / condy resolution, so
     * it must not throw: a {@code BootstrapMethodError} here is an outage. Anything unexpected
     * leaves {@code a[0]} alone and answers {@code false}, which the caller's
     * {@code INSTANCEOF [Z} guard turns into a throwaway array (F3).
     */
    static final class Data {
        @Override
        public boolean equals(Object args) {
            try {
                if (!(args instanceof Object[])) return super.equals(args);
                Object[] a = (Object[]) args;
                if (a.length < 2 || a.length > 3
                        || !(a[0] instanceof String) || !(a[1] instanceof Integer)) {
                    return super.equals(args);
                }
                String className = (String) a[0];
                int probeCount = ((Integer) a[1]).intValue();
                if (a.length == 3 && a[2] instanceof Class) {
                    ProbeHolder.registerLoadedClass(className, (Class<?>) a[2]);
                }
                // ProbeHolder.get allocates or returns the existing array and cannot throw; a[0]
                // is therefore ALWAYS replaced by a boolean[] of the right length before the
                // caller's CHECKCAST [Z sees it. Leaving the String in place would raise
                // ExceptionInInitializerError inside application code.
                a[0] = ProbeHolder.get(className, probeCount);
                return true;
            } catch (Throwable t) {
                // Deliberately silent and deliberately last: logging from here could itself
                // fail, and the caller already has a correct degraded path.
                return false;
            }
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private BootstrapBridge() { throw new AssertionError(); }
}
