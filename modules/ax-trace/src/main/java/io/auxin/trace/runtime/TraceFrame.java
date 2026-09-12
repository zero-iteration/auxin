package io.auxin.trace.runtime;

import java.util.ArrayList;
import java.util.List;

/** One node of the frame tree. Mutated only by the thread that pushed it. */
public final class TraceFrame {

    /** Recorded on the thread the request arrived on. */
    public static final String VIA_REQUEST = null;
    /** Recorded on a pool thread the context was carried to by an executor wrapper. */
    public static final String VIA_EXECUTOR = "executor";
    /** Recorded on a {@code ForkJoinPool.commonPool()} worker inside a parallelStream window. */
    public static final String VIA_COMMON_POOL = "commonPool";

    public final int frameId;
    public final int depth;
    public final TraceFrame parent;
    public final String via;

    public List<TraceFrame> children;
    public List<Observation> entryObs;
    public List<Observation> exitObs;
    public List<ArmRecord> arms;
    public Observation returnObs;
    public String threwClass;
    public long startNanos;
    public long endNanos;
    public boolean closed;
    /** Set when the observation or arm cap bit on this frame. */
    public boolean truncated;
    /** Pass-throughs this frame stands in for, after collapsing. */
    public int collapsed;
    /**
     * Identical sibling frames this one stands in for, after folding. Set by the document
     * builder on the dispatcher thread; never touched by an application thread.
     */
    public int repeated;

    /** One recorded conditional. Operands only; the opcode lives in the {@link SiteRegistry}. */
    public static final class ArmRecord {
        public final int armId;
        public final long a;
        public final long b;

        ArmRecord(int armId, long a, long b) {
            this.armId = armId;
            this.a = a;
            this.b = b;
        }
    }

    TraceFrame(int frameId, int depth, TraceFrame parent, String via) {
        this.frameId = frameId;
        this.depth = depth;
        this.parent = parent;
        this.via = via;
    }

    void addChild(TraceFrame f) {
        if (children == null) children = new ArrayList<TraceFrame>(4);
        children.add(f);
    }

    void addEntry(Observation o) {
        if (entryObs == null) entryObs = new ArrayList<Observation>(4);
        entryObs.add(o);
    }

    void addExit(Observation o) {
        if (exitObs == null) exitObs = new ArrayList<Observation>(4);
        exitObs.add(o);
    }

    void addArm(ArmRecord r) {
        if (arms == null) arms = new ArrayList<ArmRecord>(4);
        arms.add(r);
    }

    public int obsCount() {
        return (entryObs == null ? 0 : entryObs.size()) + (exitObs == null ? 0 : exitObs.size());
    }

    public int armCount() { return arms == null ? 0 : arms.size(); }

    /**
     * THE VOLUME CONTROL. A frame is a pass-through when it changed no outcome we can see:
     * it threw nothing, took no recorded branch arm, returned nothing interesting, and every
     * exit observation says exactly what its entry observation said.
     *
     * <p>One search in the field trial was 5,825 invocations across 196 methods. Full fidelity
     * there is unreadable by a human and useless to an agent, and the frames that matter are
     * precisely the ones this predicate excludes: the throw, the branch that went the other way,
     * the list that came out shorter than it went in.
     *
     * <p>Evaluated on the trace-builder thread, after the request has finished, so the volume
     * control costs the application nothing. A collapsed frame's children are kept and
     * re-parented — collapsing a pass-through must never hide what it called.
     */
    public boolean isPassThrough() {
        if (threwClass != null) return false;
        if (armCount() > 0) return false;
        if (truncated) return false;
        if (!returnAddsNothing()) return false;
        int in = entryObs == null ? 0 : entryObs.size();
        int out = exitObs == null ? 0 : exitObs.size();
        if (in == 0 && out == 0) return true;         // nothing observed at all
        if (in != out) return false;
        for (int i = 0; i < in; i++) {
            // Exit observations are emitted in the same order as entry observations, because the
            // emitter walks the same parameter list in both places.
            if (!entryObs.get(i).sameAs(exitObs.get(i))) return false;
        }
        return true;
    }

    /**
     * Did the return value say anything the caller did not already know?
     *
     * <p>Found by the smoke suite: a literal pass-through — {@code List f(List xs) { return xs; }}
     * — was NOT being collapsed, because its return observation is a {@code size} and the first
     * rule was "any size-bearing return is informative". It is not informative when it is the
     * same size as an argument that went in: the method handed back what it was given. So a
     * return that {@code sameAs} one of the entry observations counts as nothing.
     */
    private boolean returnAddsNothing() {
        if (returnObs == null) return true;
        if (Observation.NULL.equals(returnObs.kind)) return true;
        if (Observation.CLASS.equals(returnObs.kind)
                && (returnObs.children == null || returnObs.children.isEmpty())) {
            return true;
        }
        if (entryObs != null) {
            for (int i = 0; i < entryObs.size(); i++) {
                if (returnObs.sameAs(entryObs.get(i))) return true;
            }
        }
        return false;
    }

    /**
     * The collection-size delta for one observed parameter, or {@link Long#MIN_VALUE} when there
     * is no pair of sizes to subtract. "126 in, 94 out" is this number.
     */
    public long sizeDelta(int index) {
        if (entryObs == null || exitObs == null) return Long.MIN_VALUE;
        if (index >= entryObs.size() || index >= exitObs.size()) return Long.MIN_VALUE;
        Observation a = entryObs.get(index);
        Observation b = exitObs.get(index);
        if (!Observation.SIZE.equals(a.kind) || !Observation.SIZE.equals(b.kind)) {
            return Long.MIN_VALUE;
        }
        return b.num - a.num;
    }
}
