package io.auxin.trace.transport;

import io.auxin.trace.config.TraceOptions;
import io.auxin.trace.runtime.TraceContext;
import io.auxin.trace.runtime.TraceGate;
import io.auxin.trace.runtime.TraceHealth;
import io.auxin.trace.util.TLog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One daemon thread. Takes finished {@link TraceContext}s off a bounded queue, builds the
 * document and posts it.
 *
 * <h3>Why not on the request thread</h3>
 * Building the document walks the whole frame tree, does the pass-through collapse and produces
 * a JSON string that can reach megabytes. On the request thread that would add the trace's
 * serialisation cost to the latency of the request being traced — which is precisely the number
 * a person tracing a slow request is trying to read. The request thread's only work at the end
 * of a trace is one {@link ArrayBlockingQueue#offer}.
 *
 * <h3>Bounded, and dropping is visible</h3>
 * The queue is {@code ax.trace.queue.capacity} deep (default 16, which is more than three
 * minutes of the default rate cap). A full queue drops the OLDEST document and counts it:
 * dropping silently would make a missing trace indistinguishable from a request that was never
 * traced, which is the failure mode PLAN-v2's C37 exists to forbid.
 */
public final class TraceDispatcher implements TraceGate.TraceSink, Runnable {

    private final ArrayBlockingQueue<TraceContext> queue;
    private final TraceDocument builder;
    private final TraceSender sender;
    private final String dumpDir;
    private volatile Thread thread;
    private volatile boolean running = true;
    private volatile long documentsBuilt;

    public TraceDispatcher(TraceOptions o, TraceDocument builder, TraceSender sender) {
        this.queue = new ArrayBlockingQueue<TraceContext>(o.queueCapacity);
        this.builder = builder;
        this.sender = sender;
        this.dumpDir = o.dumpDir;
    }

    public void start() {
        Thread t = new Thread(this, "ax-trace-dispatch");
        t.setDaemon(true);                      // never hold up JVM exit
        t.setPriority(Thread.MIN_PRIORITY);     // a trace must never outbid the application
        thread = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    public long documentsBuilt() { return documentsBuilt; }

    public int queued() { return queue.size(); }

    @Override
    public void submit(TraceContext ctx) {
        if (!queue.offer(ctx)) {
            // Drop the oldest, keep the newest: a person who just traced a request wants THAT
            // request, and the one from two minutes ago has already been read or abandoned.
            queue.poll();
            TraceHealth.DOCS_DROPPED_QUEUE.incrementAndGet();
            if (!queue.offer(ctx)) TraceHealth.DOCS_DROPPED_QUEUE.incrementAndGet();
        }
    }

    /** Blocks until the queue is empty or the deadline passes. Test/ops hook. */
    public boolean drain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty()) return true;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return queue.isEmpty();
            }
        }
        return queue.isEmpty();
    }

    /**
     * How long the gate may stay non-zero with nothing in flight before it is reset.
     *
     * <p>Generous on purpose: a legitimately slow traced request must never be reaped, and 5
     * minutes is longer than any request worth tracing. The leak this guards against is narrow —
     * the entry method always carries a {@code catch (Throwable)} (an entry that cannot is
     * refused outright), so {@code TraceGate.end()} runs on every path; only a thread that
     * *dies* between begin and end can leave the gate up. But a stuck gate makes every probe in
     * the JVM pay a ThreadLocal read for ever, which is the one cost this design cannot afford,
     * so the valve exists.
     */
    private static final long STALE_TRACE_MS = 300000L;

    private long gateNonZeroSinceMs;

    @Override
    public void run() {
        while (running) {
            TraceContext ctx;
            try {
                ctx = queue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            reapStale();
            if (ctx == null) continue;
            gateNonZeroSinceMs = 0;
            try {
                String json = builder.build(ctx);
                documentsBuilt++;
                dump(ctx.traceId, json);
                sender.send(json);
            } catch (Throwable t) {
                TraceHealth.DOCS_FAILED.incrementAndGet();
                if (TraceHealth.warnOnce("documentBuildFailed")) {
                    TLog.warn("building a trace document failed; the trace is lost and the "
                            + "application is unaffected: " + t, t);
                }
            }
        }
    }

    /**
     * The safety valve, the same one {@code EdgeRuntime.reapStaleTraces} is. Resetting the gate
     * can at worst truncate one genuinely in-flight trace: its owning thread's {@code end()} then
     * decrements past zero and is clamped.
     */
    private void reapStale() {
        try {
            if (io.auxin.trace.runtime.TraceRuntime.activeTraces() == 0) {
                gateNonZeroSinceMs = 0;
                return;
            }
            long now = System.currentTimeMillis();
            if (gateNonZeroSinceMs == 0) {
                gateNonZeroSinceMs = now;
                return;
            }
            if (now - gateNonZeroSinceMs < STALE_TRACE_MS) return;
            if (io.auxin.trace.runtime.TraceRuntime.reapStaleTraces()) {
                gateNonZeroSinceMs = 0;
                TLog.warn("the trace gate stayed non-zero for " + (STALE_TRACE_MS / 1000)
                        + "s with no trace completing -- a request thread almost certainly died "
                        + "between activation and completion. The gate has been reset so that "
                        + "probes stop paying a thread-local read. One in-flight trace may have "
                        + "been truncated.");
            }
        } catch (Throwable t) {
            TLog.debug("stale-trace reap failed", t);
        }
    }

    /** {@code ax.trace.dump.dir}: write every document to disk. The smoke suite reads these. */
    private void dump(String traceId, String json) {
        if (dumpDir == null || dumpDir.isEmpty()) return;
        try {
            File dir = new File(dumpDir);
            dir.mkdirs();
            File f = new File(dir, "trace-" + traceId + ".json");
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(json.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            TLog.debug("trace dump failed", t);
        }
    }
}
