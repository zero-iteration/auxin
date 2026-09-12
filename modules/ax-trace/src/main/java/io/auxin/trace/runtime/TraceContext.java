package io.auxin.trace.runtime;

/**
 * One traced request. Created by {@link TraceGate#begin} on the request thread, carried to pool
 * threads by {@link Propagation}, and handed to the trace-builder thread when the request ends.
 *
 * <h3>Thread safety, and where it is bought</h3>
 * The frame TREE is shared across threads (an executor task's frames hang off the frame that
 * submitted it), so every mutation of the tree — {@code addChild}, the frame counter, the
 * truncation flags — happens under {@link #lock}. The per-thread CURSOR (which frame is
 * currently open on THIS thread) is not shared and lives in {@link Cursor}, a thread-local
 * object with no synchronisation at all.
 *
 * <p>That split is the reason a traced request on a thread pool does not serialise: the lock is
 * taken once per frame push and once per frame close, never per observation (observations are
 * appended to a frame only by the thread that pushed it, before any other thread can see it) —
 * and never, ever on an untraced request, which does not reach this class.
 */
public final class TraceContext {

    /** Per-thread cursor into the shared tree. One per (thread, trace). */
    static final class Cursor {
        final TraceContext ctx;
        TraceFrame current;
        int depth;
        final String via;
        /**
         * RE-ENTRANCY DEPTH. Non-zero while this thread is inside the tracer's own reflective
         * projection, during which no frame may be pushed and no observation recorded.
         *
         * <p>Found by the smoke suite: {@code traceapp.Fare} is in the traced scope, so
         * {@code Fare.getCarrier()} is instrumented; the projection then CALLS
         * {@code getCarrier()} reflectively, which pushes a frame, whose own parameters are
         * observed, which may project again. The first run produced 1,328 common-pool frames of
         * which most were the tracer observing itself. Per-thread, on the cursor, so the check
         * costs nothing on any path that is not already inside a trace.
         */
        int suppress;

        Cursor(TraceContext ctx, TraceFrame at, int depth, String via) {
            this.ctx = ctx;
            this.current = at;
            this.depth = depth;
            this.via = via;
        }
    }

    public final String traceId;
    public final long startedAtMs;
    public final long startNanos;
    public final int maxFrames;
    public final int maxDepth;
    public final int maxObsPerFrame;
    public final int maxArmsPerFrame;

    final Object lock = new Object();

    public final TraceFrame root;
    private int frames;
    private boolean frameCapHit;
    private boolean depthCapHit;
    /**
     * Latched when the request ends. A borrowed common-pool worker can still be running and
     * still hold a cursor into this tree; this is what stops it appending to a document that is
     * already on its way to the collector. Volatile, and the ONLY field a foreign thread reads
     * without the lock.
     */
    private volatile boolean finished;

    public volatile long endNanos;
    public volatile String requestMethod;
    public volatile String requestPath;
    public volatile int threadsJoined = 1;
    public volatile int commonPoolJoins;

    public TraceContext(String traceId, int maxFrames, int maxDepth, int maxObsPerFrame,
                        int maxArmsPerFrame) {
        this.traceId = traceId;
        this.startedAtMs = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
        this.maxFrames = maxFrames;
        this.maxDepth = maxDepth;
        this.maxObsPerFrame = maxObsPerFrame;
        this.maxArmsPerFrame = maxArmsPerFrame;
        // A synthetic root, so that a request whose entry method is itself uninstrumented still
        // produces a well-formed document rather than a forest.
        this.root = new TraceFrame(-1, 0, null, null);
        this.root.startNanos = this.startNanos;
    }

    Cursor cursorAt(TraceFrame at, int depth, String via) {
        return new Cursor(this, at, depth, via);
    }

    /** Latches this context closed. After it, {@link #push} refuses everything. */
    void finish() {
        finished = true;
        endNanos = System.nanoTime();
    }

    public boolean finished() { return finished; }

    /** @return the new frame, or null when a cap refuses it. Under the tree lock. */
    TraceFrame push(int frameId, TraceFrame parent, int depth, String via) {
        if (finished) return null;
        synchronized (lock) {
            if (finished) return null;
            if (frames >= maxFrames) {
                if (!frameCapHit) {
                    frameCapHit = true;
                    TraceHealth.FRAMES_TRUNCATED_TOTAL.incrementAndGet();
                }
                return null;
            }
            if (depth >= maxDepth) {
                if (!depthCapHit) {
                    depthCapHit = true;
                    TraceHealth.FRAMES_TRUNCATED_DEPTH.incrementAndGet();
                }
                return null;
            }
            TraceFrame f = new TraceFrame(frameId, depth, parent, via);
            f.startNanos = System.nanoTime();
            (parent == null ? root : parent).addChild(f);
            frames++;
            TraceHealth.FRAMES_RECORDED.incrementAndGet();
            return f;
        }
    }

    public int frameCount() { synchronized (lock) { return frames; } }

    public boolean frameCapHit() { synchronized (lock) { return frameCapHit; } }

    public boolean depthCapHit() { synchronized (lock) { return depthCapHit; } }

    /** Called from a pool thread when it joins this trace. */
    void joined(boolean commonPool) {
        synchronized (lock) {
            threadsJoined++;
            if (commonPool) commonPoolJoins++;
        }
    }

    public long durationNanos() {
        long end = endNanos;
        return (end == 0 ? System.nanoTime() : end) - startNanos;
    }
}
