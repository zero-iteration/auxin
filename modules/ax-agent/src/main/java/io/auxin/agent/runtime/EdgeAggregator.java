package io.auxin.agent.runtime;

/**
 * ALL call-edge aggregation, owned by the drain thread and by nothing else (C29).
 *
 * <p>Single-writer by construction, therefore every field here is plain: no atomics, no locks,
 * no {@code LongAdder}. This is the same rule tier-2 follows and the same reason A8's
 * "4 contended atomic RMWs per record" is a non-problem in this design rather than a mitigated
 * one.
 *
 * <p>An open-addressed {@code long -> long} table rather than a {@code HashMap<Long, long[]>}:
 * the key is already a packed {@code long} (see {@link EdgeEvents}), and boxing one {@code Long}
 * plus one counter object per distinct edge is allocation the drain thread does not need to do.
 *
 * <p><b>Bounded.</b> The table refuses new keys past {@code ax.edges.max.distinct} and counts
 * the refusals. Edges already in the table keep counting, so a truncated window is a subset of
 * the real graph and never a wrong count — and the wire says how many distinct edges it could
 * not carry.
 */
public final class EdgeAggregator implements Ring.EventHandler {

    private static final int INITIAL = 1024;

    private long[] keys = new long[INITIAL];
    private long[] counts = new long[INITIAL];
    private int size;
    private int mask = INITIAL - 1;

    private final int maxDistinct;

    private long eventsProcessed;
    private long distinctRefused;

    public EdgeAggregator(int maxDistinct) {
        this.maxDistinct = maxDistinct < 1 ? 1 : maxDistinct;
    }

    @Override
    public void onEvent(long event) {
        final long key = EdgeEvents.key(event);
        eventsProcessed++;
        int i = index(key);
        if (i >= 0) {
            counts[i]++;
            return;
        }
        if (size >= maxDistinct) {
            distinctRefused++;
            return;
        }
        insert(key, 1L);
    }

    /**
     * @return the slot holding {@code key}, or -1. Key 0 cannot occur —
     *         {@link EdgeEvents#key} keeps the marker bit set for exactly this reason.
     */
    private int index(long key) {
        int i = hash(key) & mask;
        while (keys[i] != 0L) {
            if (keys[i] == key) return i;
            i = (i + 1) & mask;
        }
        return -1;
    }

    private void insert(long key, long count) {
        if ((size + 1) * 10 >= keys.length * 6) grow();
        int i = hash(key) & mask;
        while (keys[i] != 0L) i = (i + 1) & mask;
        keys[i] = key;
        counts[i] = count;
        size++;
    }

    private void grow() {
        final long[] oldKeys = keys;
        final long[] oldCounts = counts;
        final int n = oldKeys.length * 2;
        keys = new long[n];
        counts = new long[n];
        mask = n - 1;
        size = 0;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldKeys[j] != 0L) insert(oldKeys[j], oldCounts[j]);
        }
    }

    /** Fibonacci mixing: the key's two 24-bit halves are dense, so the low bits alone collide. */
    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40);
    }

    // ---------------- window serialisation (drain thread) ----------------

    public interface EdgeVisitor {
        void edge(int callerEdgeId, int calleeEdgeId, long count);
    }

    public void forEach(EdgeVisitor v) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == 0L || counts[i] == 0L) continue;
            v.edge(EdgeEvents.caller(keys[i]), EdgeEvents.callee(keys[i]), counts[i]);
        }
    }

    public long eventsProcessed() { return eventsProcessed; }

    public long distinctRefused() { return distinctRefused; }

    public int distinct() { return size; }

    /**
     * After a window has been serialised.
     *
     * <p>The keys are kept and only the counts are cleared, exactly as {@code Tier2Aggregator}
     * keeps its {@code MethodStats}: an application's call graph is stable between windows, so
     * re-hashing it every flush would be pure churn. {@link #forEach} skips zero counts, so a
     * retained key that stops firing does not appear on the wire.
     */
    public void resetWindow() {
        for (int i = 0; i < counts.length; i++) counts[i] = 0L;
    }
}
