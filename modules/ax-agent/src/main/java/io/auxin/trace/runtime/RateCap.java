package io.auxin.trace.runtime;

/**
 * Traces per minute. A sliding window over the last {@code n} grant timestamps, so the cap is
 * "n in any 60 seconds" rather than "n per calendar minute" — a fixed-window cap lets 2n traces
 * through across a boundary, which is exactly the burst it exists to prevent.
 *
 * <h3>Why this is table stakes and not a nicety</h3>
 * A trace does 10-100x the work of the request it measures: a frame push, a clock read, a
 * reflective projection and a document node per instrumented call. An unauthenticated header
 * that activates one is therefore an amplification vector — a single client can make the service
 * spend its CPU on tracing rather than serving. The cap bounds the damage even when the token
 * check is misconfigured, which is the point of having two controls.
 *
 * <p>{@code ax.trace.rate.per.minute=0} means "never grant", which is a usable kill switch
 * distinct from {@code ax.trace.enabled=false}: the instrumentation stays in place (so it can be
 * turned back on without a restart) but no request is ever traced.
 *
 * <p>Contended by design — it is consulted once per request carrying the header, never per call,
 * so a single lock is the right instrument.
 */
public final class RateCap {

    private static final long WINDOW_MS = 60000L;

    private final int limit;
    private final long[] grants;
    private int next;
    private int filled;

    public RateCap(int perMinute) {
        this.limit = perMinute < 0 ? 0 : perMinute;
        this.grants = new long[Math.max(1, this.limit)];
    }

    public int limit() { return limit; }

    /** @return true when a trace may start now. */
    public synchronized boolean tryAcquire() {
        if (limit == 0) return false;
        final long now = System.currentTimeMillis();
        if (filled >= limit) {
            // grants[next] is the OLDEST of the last `limit` grants.
            long oldest = grants[next];
            if (now - oldest < WINDOW_MS) return false;
        }
        grants[next] = now;
        next = (next + 1) % limit;
        if (filled < limit) filled++;
        return true;
    }

    /** How many grants are still inside the window. Ops/test hook. */
    public synchronized int inWindow() {
        final long now = System.currentTimeMillis();
        int n = 0;
        for (int i = 0; i < filled; i++) {
            if (now - grants[i] < WINDOW_MS) n++;
        }
        return n;
    }
}
