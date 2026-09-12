package io.auxin.agent.runtime;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The probe array holder. Instrumented application code reaches exactly two things here:
 * {@link #bootstrap} (the {@code ConstantDynamic} bootstrap method, class file &gt;= 55) and
 * {@link #get} (the pre-55 static-field fallback).
 *
 * <p><b>Reachability.</b> A plain agent-jar class is only reachable from application code when
 * that code's class loader delegates to the system loader. Where it does not — OSGi, JBoss
 * Modules, JPMS named modules, some Spring Boot fat jars — {@link BootstrapBridge} installs
 * {@code java.lang.$Auxin} and the instrumented class reaches its array through
 * {@code java.lang} only, never naming this class at all (VALIDATION A10/C33, C34). The choice is
 * made per class loader by {@code LoaderVisibility}, and a loader that can see neither gets no
 * probes rather than a {@code NoClassDefFoundError} in a business method.
 *
 * <p>Memory is bounded by the manifest, not by the number of loaded classes: only classes the
 * build-time manifest knows about ever get an array (VALIDATION A14 defect 3 — apps that
 * generate classes forever otherwise grow forever).
 */
public final class ProbeHolder {

    /** dotted class name -> probe array. Accumulated, NEVER cleared (A14 defect 5). */
    private static final ConcurrentHashMap<String, boolean[]> ARRAYS =
            new ConcurrentHashMap<String, boolean[]>();

    /** dotted class name -> the loaded Class, captured from the condy Lookup; strip handle. */
    private static final ConcurrentHashMap<String, Class<?>> CLASSES =
            new ConcurrentHashMap<String, Class<?>>();

    /** Classes we instrumented at load. Strictly a subset of {@link #SEEN}. */
    private static final ConcurrentHashMap<String, Boolean> INSTRUMENTED =
            new ConcurrentHashMap<String, Boolean>();

    /**
     * Internal names the transformer was handed, recorded BEFORE any skip decision
     * (G5-FINDING-4).
     *
     * <p>Until G5 this set did not exist and {@code WindowPayload.classesLoaded} was built from
     * {@link #INSTRUMENTED}, so <b>every</b> skip reason — a pure interface with no
     * probe-eligible method, a schemaHash mismatch, a budget breach, an ignored prefix — also
     * erased the class from the "loaded" set. C10 wants "loaded" to be an independent fact
     * precisely so the analysis layer can tell "this pod never loaded the class" from "we
     * declined to instrument it"; as implemented the two were indistinguishable and lazily
     * loaded code could be labelled dead.
     *
     * <p>Bounded to non-generated classes inside {@code ax.include.packages}, and hard-capped
     * on top of that: these names travel on the wire, and an application that generates classes
     * for ever would otherwise grow this map for ever (A14 defect 3, jacoco#655 — 2GB of
     * {@code $$EnhancerBySpringCGLIB$$}). Runtime-generated classes are excluded because
     * CONTRACTS section 1 keeps them out of the build manifest too, so they can never be the
     * subject of a dead-code verdict and their absence here cannot mislabel anything. Reaching
     * the cap is reported, never silent.
     */
    private static final ConcurrentHashMap<String, Boolean> SEEN =
            new ConcurrentHashMap<String, Boolean>();

    /** Enough for any real application's own packages; a backstop, not a budget. */
    private static final int SEEN_CAP = 20000;

    private static volatile boolean seenTruncated;

    /**
     * Condy bootstrap. The constant's declared descriptor is {@code Ljava/lang/Object;} and the
     * call site does {@code CHECKCAST [Z} — JaCoCo's JDK-8216970 workaround, proven in E2.
     */
    public static boolean[] bootstrap(MethodHandles.Lookup lookup, String constantName,
                                      Class<?> constantType, String className, int probeCount) {
        if (lookup != null) {
            try {
                registerLoadedClass(className, lookup.lookupClass());
            } catch (Throwable ignored) {
                // a missing strip handle costs de-instrumentation, never correctness
            }
        }
        return get(className, probeCount);
    }

    /**
     * Records the Tier-1b strip handle for a class.
     *
     * <p>{@code DrainThread.stripCoveredClasses()} can only retransform a class it holds a
     * {@link Class} object for, and the only place that object is available is inside condy
     * resolution ({@code lookup.lookupClass()}). Called from {@link #bootstrap} on the direct
     * condy path and from {@code BootstrapBridge.Data.equals} on the bridge path (F2) — before
     * F2 the bridge path had no way to supply one, so bridged classes could never be
     * de-instrumented and kept their probes for the life of the JVM.
     */
    public static void registerLoadedClass(String className, Class<?> loaded) {
        if (className == null || loaded == null) return;
        CLASSES.putIfAbsent(className, loaded);
    }

    /** Pre-55 fallback entry point, called once from the class's {@code <clinit>}. */
    public static boolean[] get(String className, int probeCount) {
        boolean[] a = ARRAYS.get(className);
        if (a != null) return a;
        boolean[] created = new boolean[probeCount];
        boolean[] prev = ARRAYS.putIfAbsent(className, created);
        return prev != null ? prev : created;
    }

    /** Called by the installer at transform time; the class is about to be defined. */
    public static void registerInstrumented(String className, int probeCount) {
        INSTRUMENTED.put(className, Boolean.TRUE);
        get(className, probeCount);
    }

    public static boolean[] peek(String className) {
        return ARRAYS.get(className);
    }

    public static Class<?> loadedClass(String className) {
        return CLASSES.get(className);
    }

    public static List<String> instrumentedClasses() {
        return new ArrayList<String>(INSTRUMENTED.keySet());
    }

    /**
     * The transformer saw this class. Called from {@code ProbeInstaller} at initial load, before
     * and independent of every skip decision (G5-FINDING-4). Internal form is stored so the
     * transform path allocates nothing; the conversion to dotted names happens once per flush.
     */
    public static void observeLoad(String internalName) {
        if (internalName == null) return;
        if (SEEN.containsKey(internalName)) return;
        if (SEEN.size() >= SEEN_CAP) {
            seenTruncated = true;
            return;
        }
        SEEN.put(internalName, Boolean.TRUE);
    }

    /** Every class the transformer saw, dotted, whether or not it was instrumented. */
    public static List<String> loadedClassNames() {
        List<String> out = new ArrayList<String>(SEEN.size());
        for (String n : SEEN.keySet()) out.add(n.replace('/', '.'));
        return out;
    }

    /** True once {@link #SEEN_CAP} was hit: {@code classesLoaded} is no longer complete. */
    public static boolean loadedTruncated() { return seenTruncated; }

    public static Map<String, boolean[]> arrays() {
        return ARRAYS;
    }

    private ProbeHolder() { throw new AssertionError(); }
}
