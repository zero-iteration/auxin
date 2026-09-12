package io.auxin.agent.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-window exception-class name table of CONTRACTS section 2 <b>v4</b> (BUG #24).
 *
 * <p>{@link Tier2Aggregator.MethodStats#errorCounts} is indexed by the JVM-lifetime id
 * {@link ErrorIds} hands out, and {@code ErrorIds} holds the names. Until v4 none of it reached
 * the wire: {@code Batch} wrote the scalar {@code errors} and {@code errorTypes}, so
 * "<i>what does it throw</i>" arrived as a count without an id table a reader could resolve
 * across records.
 *
 * <p><b>Ids here are WINDOW-LOCAL and deliberately renumbered.</b> A global id would happen to
 * be stable across windows, and a reader that noticed would start storing ids instead of names —
 * which the contract forbids, because nothing in the protocol promises it. Renumbering from 1 on
 * every window makes the rule enforceable by construction: id 1 really is a different class in
 * the next window whenever a different class errored first.
 *
 * <p>Three rules this class exists to keep:
 * <ul>
 *   <li><b>Only referenced ids are emitted.</b> An entry appears because a record in THIS window
 *       counted an error against it, never because the JVM has seen the class at some point.
 *       After {@link ErrorIds}' 254 slots are exhausted the global table is large and permanent;
 *       the window table stays as small as the window's actual errors.</li>
 *   <li><b>255 is the overflow bucket</b>, named {@link ErrorIds#OVERFLOW_NAME}. Several global
 *       classes can fold into it, so counts against it are SUMMED, never overwritten.</li>
 *   <li><b>No name is ever invented.</b> An id the name table cannot resolve folds into the
 *       overflow bucket rather than shipping a guess; {@code sum(errorsByClass) <= errors} is a
 *       legal window and the remainder is the reader's "unattributed", not our licence to make
 *       a class up.</li>
 * </ul>
 *
 * <p>Drain thread only, and one instance is owned by {@link DrainThread} and {@link #reset()} at
 * the top of every window: this is flush work, so it never touches an application thread.
 */
public final class ErrorClassTable {

    /** Window-local ids run 1..254; 255 is the overflow bucket, exactly as the global ids do. */
    public static final int MAX_LOCAL_ID = 254;

    /** Global id -&gt; window-local id, or 0 when this window has not referenced it yet. */
    private final int[] localByGlobal = new int[ErrorIds.OVERFLOW + 1];
    private final Map<Integer, String> names = new LinkedHashMap<Integer, String>();
    private int next = 1;

    /**
     * @param globalId the id stored in {@code MethodStats.errorCounts}
     * @return the id to write into {@code errorsByClass} for this window. Never 0: the caller
     *         only asks about ids it counted an error against.
     */
    public int localIdFor(int globalId) {
        if (globalId <= ErrorIds.NONE || globalId >= ErrorIds.OVERFLOW) return overflow();
        final int existing = localByGlobal[globalId];
        if (existing != 0) return existing;
        // Structurally unreachable while ErrorIds caps itself at 254 names, so it is handled
        // rather than asserted: the alternative to folding a 255th class into the overflow
        // bucket is emitting an id with no entry in the table, which is the one thing a reader
        // of this field cannot recover from.
        if (next > MAX_LOCAL_ID) return overflow();
        final String name = ErrorIds.name(globalId);
        if (name == null || name.isEmpty() || "unknown".equals(name)) return overflow();
        final int local = next++;
        localByGlobal[globalId] = local;
        names.put(Integer.valueOf(local), name);
        return local;
    }

    private int overflow() {
        names.put(Integer.valueOf(ErrorIds.OVERFLOW), ErrorIds.OVERFLOW_NAME);
        return ErrorIds.OVERFLOW;
    }

    /** The table to serialise, in first-seen order. Live map: copy it if it must outlive a window. */
    public Map<Integer, String> names() { return names; }

    public int size() { return names.size(); }

    public boolean isEmpty() { return names.isEmpty(); }

    /** Start a new window. Reused rather than reallocated: one table for the life of the JVM. */
    public void reset() {
        Arrays.fill(localByGlobal, 0);
        names.clear();
        next = 1;
    }
}
