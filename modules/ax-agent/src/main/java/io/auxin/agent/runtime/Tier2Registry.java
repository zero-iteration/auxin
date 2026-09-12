package io.auxin.agent.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * methodId -&gt; (class, probe index) for tier-2. Ids are dense and assigned at transform time on
 * the transforming thread; the emitted bytecode pushes the id as a bytecode constant, so nothing
 * is looked up on the application thread.
 *
 * <p>The id is NOT the identity that leaves the JVM: the wire carries (class, idx) from the
 * build-time manifest (CONTRACTS section 1, identity = buildSha + class + descriptor).
 */
public final class Tier2Registry {

    public static final class Entry {
        public final String className;   // dotted
        public final int idx;            // manifest probe index
        public final String methodName;
        public final String desc;

        Entry(String className, int idx, String methodName, String desc) {
            this.className = className;
            this.idx = idx;
            this.methodName = methodName;
            this.desc = desc;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();

    /** @return the assigned methodId, or -1 when the 16-bit id space is exhausted. */
    public static synchronized int register(String className, int idx, String methodName, String desc) {
        if (ENTRIES.size() > Events.MAX_METHOD_ID) return -1;
        ENTRIES.add(new Entry(className, idx, methodName, desc));
        return ENTRIES.size() - 1;
    }

    public static synchronized Entry get(int methodId) {
        return methodId >= 0 && methodId < ENTRIES.size() ? ENTRIES.get(methodId) : null;
    }

    public static synchronized int size() { return ENTRIES.size(); }

    private Tier2Registry() { throw new AssertionError(); }
}
