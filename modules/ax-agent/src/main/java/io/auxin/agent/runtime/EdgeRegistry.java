package io.auxin.agent.runtime;

import io.auxin.agent.health.Health;

/**
 * edgeId -&gt; (class, manifest probe index) for the call-edge tier.
 *
 * <p>Same contract as {@link Tier2Registry}, and for the same reason: the id is a bytecode
 * constant pushed at the call site, so <b>nothing is looked up on the application thread</b>.
 * The identity that leaves the JVM is still {@code (class, idx)} from the build-time manifest
 * (CONTRACTS section 1), never an id of ours.
 *
 * <p>Separate from {@link Tier2Registry} on purpose. Tier-2's id space is the 50-200 boundary
 * methods and is 16 bits wide on the wire-side encoding; the edge tier's id space is every
 * instrumented method in scope, which is three orders of magnitude larger. Sharing one dense
 * space would either cap the edge tier at 65536 methods or widen tier-2's packed event for no
 * benefit.
 *
 * <p>Parallel arrays rather than a {@code List<Entry>}: at 50k instrumented methods an entry
 * object per method is ~2MB of agent heap that exists only to hold two values, and the class
 * name strings are shared with the caller (one instance per class, not per method).
 */
public final class EdgeRegistry {

    private static final int INITIAL = 1024;

    private static String[] classNames = new String[INITIAL];
    private static int[] indices = new int[INITIAL];
    private static int size;

    /**
     * {@code class#idx -> edgeId}, so the two runtime copies of one class that a fat jar or a
     * child-first loader produces share an id.
     *
     * <p>That mirrors what already happens one layer down: isolation finding F5 —
     * "two copies of one class share one probe array". The wire's identity is
     * {@code (buildSha, class, idx)}, which cannot distinguish the copies either, so giving them
     * separate ids would only put two rows with identical identities in {@code edges[]} and make
     * every reader sum them.
     */
    private static final java.util.Map<String, Integer> BY_METHOD =
            new java.util.HashMap<String, Integer>();

    /**
     * @param className dotted; the SAME String instance should be passed for every method of a
     *                  class, so the registry holds one reference per class rather than per
     *                  method.
     * @param idx       the manifest probe index (CONTRACTS section 1), never visit order.
     * @return the assigned edgeId, or -1 when the 24-bit id space is exhausted.
     */
    public static synchronized int register(String className, int idx) {
        final String key = className + '#' + idx;
        Integer existing = BY_METHOD.get(key);
        if (existing != null) return existing.intValue();
        if (size > EdgeEvents.MAX_EDGE_ID) {
            Health.skip(Health.SKIP_EDGE_ID_EXHAUSTED);
            return -1;
        }
        if (size == classNames.length) {
            int n = classNames.length * 2;
            String[] cn = new String[n];
            int[] ix = new int[n];
            System.arraycopy(classNames, 0, cn, 0, size);
            System.arraycopy(indices, 0, ix, 0, size);
            classNames = cn;
            indices = ix;
        }
        classNames[size] = className;
        indices[size] = idx;
        BY_METHOD.put(key, Integer.valueOf(size));
        return size++;
    }

    /** Drain thread only. @return the dotted class name, or null when the id is unknown. */
    public static synchronized String className(int edgeId) {
        return edgeId >= 0 && edgeId < size ? classNames[edgeId] : null;
    }

    /** Drain thread only. @return the manifest probe index, or -1 when the id is unknown. */
    public static synchronized int idx(int edgeId) {
        return edgeId >= 0 && edgeId < size ? indices[edgeId] : -1;
    }

    public static synchronized int size() { return size; }

    private EdgeRegistry() { throw new AssertionError(); }
}
