package io.auxin.agent.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * ALL tier-2 aggregation, owned by the drain thread and by nothing else (C29).
 *
 * <p>Single-writer by construction, therefore every field here is plain: no LongAdder, no
 * atomics, no locks. This is dd-trace's {@code ClientStatsAggregator} model and it is what makes
 * A8's "4 contended atomic RMWs per record" a non-problem rather than a mitigation.
 */
public final class Tier2Aggregator implements Ring.EventHandler {

    public static final class MethodStats {
        public long calls;
        public long errors;
        public long timedCalls;
        public final Histogram histogram = new Histogram();
        public long[] errorCounts;   // by errorClassId, lazily allocated

        void reset() {
            calls = 0;
            errors = 0;
            timedCalls = 0;
            histogram.reset();
            if (errorCounts != null) java.util.Arrays.fill(errorCounts, 0L);
        }

        public boolean empty() { return calls == 0 && errors == 0; }
    }

    private final Map<Integer, MethodStats> byMethodId = new HashMap<Integer, MethodStats>();
    private long eventsProcessed;

    @Override
    public void onEvent(long event) {
        int methodId = Events.methodId(event);
        MethodStats s = byMethodId.get(Integer.valueOf(methodId));
        if (s == null) {
            s = new MethodStats();
            byMethodId.put(Integer.valueOf(methodId), s);
        }
        s.calls++;
        if (Events.timed(event)) {
            s.timedCalls++;
            s.histogram.record(Events.bucket(event));
        }
        int errorId = Events.errorClassId(event);
        if (errorId != ErrorIds.NONE) {
            s.errors++;
            if (s.errorCounts == null) s.errorCounts = new long[256];
            s.errorCounts[errorId]++;
        }
        eventsProcessed++;
    }

    public long eventsProcessed() { return eventsProcessed; }

    public Map<Integer, MethodStats> stats() { return byMethodId; }

    /**
     * After a window has been serialised. Drain thread only.
     *
     * <p>The map entries are kept, not removed: they are bounded by the tier-2 allowlist
     * (50-200 boundary methods), so reusing them avoids re-allocating a histogram per window.
     */
    public void resetWindow() {
        for (Map.Entry<Integer, MethodStats> e : byMethodId.entrySet()) {
            e.getValue().reset();
        }
    }
}
