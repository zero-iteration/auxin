package io.auxin.agent.runtime;

/**
 * One runtime call edge packed into one {@code long}.
 *
 * <pre>
 *   bit 63     : marker, always 1 (0 means "slot unpublished" in the ring)
 *   bits 48-62 : reserved
 *   bits 24-47 : callerEdgeId (24 bits)
 *   bits  0-23 : calleeEdgeId (24 bits)
 * </pre>
 *
 * <p><b>Why not {@link Events}.</b> A tier-2 event carries one method id (16 bits) plus a
 * duration bucket and an error class. An edge carries <i>two</i> ids and nothing else, and the
 * id space is the whole instrumented method population rather than the 50-200 tier-2
 * boundary methods — so 16 bits is not enough for either end. The two encodings do not fit in
 * one layout, which is why the edge tier gets its own ring (pre-allocated at premain) rather
 * than sharing tier-2's. The ring itself is reused verbatim: A7's "{@code MpscArrayQueue<E>}
 * forces an allocation" verdict applies identically here, and a packed {@code long} in a
 * {@code long[]} is the answer to it.
 *
 * <p>The marker bit is load bearing for the same reason as in {@link Events}: {@link Ring} uses
 * a zero slot to mean "claimed but not yet published", so no legal event may be zero.
 */
public final class EdgeEvents {

    public static final long MARKER = 1L << 63;

    /** 24 bits per end. Ids are dense and assigned at transform time by {@link EdgeRegistry}. */
    public static final int MAX_EDGE_ID = 0xFFFFFF;

    public static long pack(int callerEdgeId, int calleeEdgeId) {
        return MARKER
                | ((long) (callerEdgeId & MAX_EDGE_ID) << 24)
                | (long) (calleeEdgeId & MAX_EDGE_ID);
    }

    public static int caller(long e) { return (int) ((e >>> 24) & MAX_EDGE_ID); }

    public static int callee(long e) { return (int) (e & MAX_EDGE_ID); }

    /**
     * The aggregation key: the packed event itself, <b>marker included</b>.
     *
     * <p>The marker is what keeps the key non-zero, and {@link EdgeAggregator}'s open-addressed
     * table uses 0 for an empty slot. Clearing it would make the self edge of the very first
     * registered method (edge id 0 calling edge id 0 — a recursive method that happens to be
     * first in transform order) hash to key 0, which the table would read as an empty slot: the
     * edge would be counted into a slot that {@code forEach} then skips, and the count would
     * vanish silently. Exactly the class of bug this project exists to stop shipping.
     */
    public static long key(long e) { return e | MARKER; }

    private EdgeEvents() { throw new AssertionError(); }
}
