package io.auxin.trace.runtime;

import io.auxin.trace.util.TLog;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The application-thread path. Instrumented methods call exactly the methods in the "probe entry
 * points" section below and nothing else.
 *
 * <h3>THE GATE — copied verbatim in discipline from {@code EdgeRuntime}</h3>
 * <pre>   if (ACTIVE == 0) return;   // one static load, one branch, nothing else</pre>
 * checked <b>before any ThreadLocal read</b>, in every single probe entry point. On a request
 * that is not being traced that is the entire cost: no thread-local read, no clock, no
 * allocation, no branch on anything but a static field the JIT hoists out of loops. The number
 * is measured in {@code bench/} and reported in TRADE-OFFS.md rather than asserted.
 *
 * <p>{@link #ACTIVE} is the number of traces in flight anywhere in the JVM, and it is
 * {@code volatile} for the same reason {@code EdgeRuntime.activeTraces} is: a stuck non-zero
 * value is the one failure mode that costs money for ever, and on x86_64 a volatile int read
 * compiles to the same {@code mov} as a plain one.
 *
 * <p><b>The regime this does not make free.</b> While ANY thread is inside a traced request,
 * every OTHER thread's probes fall through the gate into a {@link ThreadLocal#get()} that finds
 * nothing. With the default rate cap (5 traces/minute) and {@code max.concurrent=1} that window
 * is a few milliseconds per minute, but it is real and the benchmark measures it as its own arm.
 *
 * <h3>Fail-open, absolutely</h3>
 * No {@code Throwable} reaches application code. Anything unexpected latches the tracer off for
 * the life of the JVM, counts it, and says it exactly once. A JVM that stops tracing is a bad
 * afternoon; a JVM that throws out of a business method is an outage.
 */
public final class TraceRuntime {

    /** THE GATE. Traces in flight anywhere in this JVM. */
    private static volatile int ACTIVE;

    /** Master switch. False by default and false whenever the tracer refused to arm. */
    private static volatile boolean enabled;

    /** Latched by {@link #failOpen}: once off, off for the life of the JVM. */
    private static volatile boolean failed;

    private static final Object LOCK = new Object();

    private static final ThreadLocal<TraceContext.Cursor> CURSOR =
            new ThreadLocal<TraceContext.Cursor>();

    /**
     * Read on the traced path only, written once at premain, deliberately not volatile: the
     * volatile write to {@link #enabled} in {@link #install} publishes them, and every reader has
     * already read {@code enabled} or {@code ACTIVE}. Moving the {@code enabled} assignment from
     * last position in {@code install} would break that.
     */
    private static int maxConcurrent = 1;
    private static boolean propagate = true;
    private static boolean commonPoolWindowEnabled = true;

    // ---- the parallelStream window (see #commonPoolArm) ----
    private static final AtomicInteger CP_ARMED = new AtomicInteger();
    private static volatile TraceContext CP_CTX;
    private static volatile TraceFrame CP_PARENT;

    public static void install(int maxConcurrentTraces, boolean propagateExecutors,
                               boolean parallelStreamWindow, boolean on) {
        maxConcurrent = maxConcurrentTraces < 1 ? 1 : maxConcurrentTraces;
        propagate = propagateExecutors;
        commonPoolWindowEnabled = parallelStreamWindow;
        enabled = on && !failed;                    // MUST be last: it publishes the three above
    }

    public static void disable() { enabled = false; }

    public static boolean enabled() { return enabled; }

    public static boolean failedOff() { return failed; }

    public static int activeTraces() { return ACTIVE; }

    public static int maxConcurrent() { return maxConcurrent; }

    /**
     * Break-glass / verification control: take the same path an unexpected {@code Throwable}
     * would take. "Fails open" is otherwise an untestable claim, and an alert on
     * {@code health.runtimeFailures} that has never fired is not an alert.
     */
    public static void forceFailOpen(String why) {
        failOpen(new IllegalStateException(why == null ? "forceFailOpen" : why));
    }

    // ================= probe entry points: the application thread =================

    /** Method entry. <b>The number that decides whether this module ships.</b> */
    public static void enter(int frameId) {
        if (ACTIVE == 0) return;
        onEnter(frameId);
    }

    /** Method return, on the normal path and from the {@code catch (Throwable)} handler. */
    public static void exit(int frameId) {
        if (ACTIVE == 0) return;
        onExit(frameId);
    }

    /** The throw. Class name only — never the message, never the stack trace (A13 / ASVS). */
    public static void threw(Throwable t, int frameId) {
        if (ACTIVE == 0) return;
        onThrew(t, frameId);
    }

    public static void obsRef(Object o, int obsId) {
        if (ACTIVE == 0) return;
        onObsRef(o, obsId);
    }

    public static void obsInt(int v, int obsId) {
        if (ACTIVE == 0) return;
        onObsInt(v, obsId);
    }

    public static void obsLong(long v, int obsId) {
        if (ACTIVE == 0) return;
        onObsLong(v, obsId);
    }

    public static void obsDouble(double v, int obsId) {
        if (ACTIVE == 0) return;
        onObsDouble(v, obsId);
    }

    /** A unary int conditional: {@code IFEQ}…{@code IFLE}. The operand, not the arm. */
    public static void armI(int v, int armId) {
        if (ACTIVE == 0) return;
        onArm(armId, v, 0);
    }

    /** A binary int conditional: {@code IF_ICMPEQ}…{@code IF_ICMPLE}. */
    public static void armII(int a, int b, int armId) {
        if (ACTIVE == 0) return;
        onArm(armId, a, b);
    }

    /**
     * {@code IFNULL} / {@code IFNONNULL}. Records <b>null-ness only</b>: the reference is not
     * retained, not stored, and not inspected beyond {@code o == null}.
     */
    public static void armA(Object o, int armId) {
        if (ACTIVE == 0) return;
        onArm(armId, o == null ? 0 : 1, 0);
    }

    /**
     * {@code IF_ACMPEQ} / {@code IF_ACMPNE}. Records whether the two references were identical —
     * one bit — and neither reference is retained.
     */
    public static void armAA(Object a, Object b, int armId) {
        if (ACTIVE == 0) return;
        onArm(armId, 0, a == b ? 0 : 1);
    }

    /** {@code TABLESWITCH} / {@code LOOKUPSWITCH}: the selector. */
    public static void armSwitch(int v, int armId) {
        if (ACTIVE == 0) return;
        onArm(armId, v, 0);
    }

    /**
     * THE {@code parallelStream} WINDOW.
     *
     * <p>The trap, named by the field reporter: {@code Collection.parallelStream()} runs its
     * pipeline on {@code ForkJoinPool.commonPool()} and escapes naive executor wrapping
     * completely. There is no {@code Runnable} and no {@code Callable} at the call site — the
     * work is a lambda that {@code java.util.stream} hands to the common pool itself — so
     * call-site wrapping (which is how {@link #wrapRunnable} and friends get into a class the
     * agent can actually see) has nothing to wrap. OpenTelemetry solves this by instrumenting
     * {@code ForkJoinTask} inside the JDK, which needs the probe to be resolvable from the
     * <b>bootstrap</b> loader — the {@code java.lang.$Auxin} problem, and ax-agent has already
     * established that a bridged CALL (as opposed to a bridged constant) is not payable.
     *
     * <p>So: the emitter detects a {@code parallelStream()} / {@code .parallel()} call in an
     * instrumented method's body and brackets that whole method with arm/disarm. While the
     * window is open, a probe running on a common-pool worker with no thread-local cursor may
     * join the trace ({@link #joinCommonPool}). The lambda bodies of the pipeline are synthetic
     * methods on the application class, so they are already instrumented; what they were missing
     * was a context, and this is the context.
     *
     * <p><b>The honest cost.</b> Any OTHER code using the common pool during the window is
     * attributed to this trace. That is why frames arriving this way are tagged
     * {@code via: "commonPool"} in the document, why the window is refused outright when more
     * than one trace is in flight (counted as {@code commonPoolWindowRefused}), and why
     * {@code ax.trace.parallelstream.window=false} turns it off. It is a narrow, labelled
     * over-capture, not a correctness claim.
     */
    public static void commonPoolArm() {
        if (ACTIVE == 0) return;
        onCommonPoolArm();
    }

    public static void commonPoolDisarm() {
        if (ACTIVE == 0) return;
        onCommonPoolDisarm();
    }

    // ================= probe entry points: executor call-site wrappers =================
    //
    // Each of these is an INVOKESTATIC inserted immediately before the submit call, in an
    // instrumented application class, so the receiver's own class is never touched and the
    // bootstrap loader is never involved. On an untraced request every one of them is
    // "one static load, one branch, return the argument unchanged" and allocates nothing.
    //
    // OpenTelemetry's executors instrumentation is the reference for WHAT to wrap (Executor,
    // ExecutorService including the 2-arg submit and invokeAll/invokeAny, the CompletableFuture
    // *Async family, ForkJoinPool) and for the rule that the context is captured at SUBMIT and
    // restored at RUN. What differs is WHERE: OTel instruments the executor classes themselves
    // because it can reach the bootstrap loader; we rewrite the call site because we cannot.

    public static Runnable wrapRunnable(Runnable r) {
        if (ACTIVE == 0) return r;
        return Propagation.wrap(r);
    }

    public static <T> Callable<T> wrapCallable(Callable<T> c) {
        if (ACTIVE == 0) return c;
        return Propagation.wrap(c);
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> s) {
        if (ACTIVE == 0) return s;
        return Propagation.wrap(s);
    }

    public static <T, R> Function<T, R> wrapFunction(Function<T, R> f) {
        if (ACTIVE == 0) return f;
        return Propagation.wrap(f);
    }

    public static <T> Consumer<T> wrapConsumer(Consumer<T> c) {
        if (ACTIVE == 0) return c;
        return Propagation.wrap(c);
    }

    public static <T, U> BiConsumer<T, U> wrapBiConsumer(BiConsumer<T, U> c) {
        if (ACTIVE == 0) return c;
        return Propagation.wrap(c);
    }

    public static <T, U, R> BiFunction<T, U, R> wrapBiFunction(BiFunction<T, U, R> f) {
        if (ACTIVE == 0) return f;
        return Propagation.wrap(f);
    }

    /** {@code invokeAll} / {@code invokeAny}: a collection of tasks, each wrapped. */
    public static Collection<?> wrapAll(Collection<?> tasks) {
        if (ACTIVE == 0) return tasks;
        return Propagation.wrapAll(tasks);
    }

    // ================= the traced path =================

    private static void onEnter(int frameId) {
        try {
            TraceContext.Cursor c = CURSOR.get();
            if (c == null) {
                c = joinCommonPool();
                if (c == null) return;              // some other thread is tracing, not us
            }
            // The tracer must not observe itself: a projected getter on an in-scope class is
            // instrumented like any other method, and recording its frame would be the tracer
            // measuring its own reflection. See TraceContext.Cursor#suppress.
            if (c.suppress > 0) return;
            TraceFrame f = c.ctx.push(frameId, c.current, c.depth, c.via);
            if (f == null) return;                  // a cap refused it; the cursor does not move
            c.current = f;
            c.depth++;
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    private static void onExit(int frameId) {
        try {
            TraceContext.Cursor c = CURSOR.get();
            if (c == null) return;
            // RESTORE semantics, not decrement semantics -- the same rule EdgeRuntime.popTo
            // documents. An exception that unwound three frames without running their handlers
            // is repaired by the first exit that does run.
            TraceFrame f = c.current;
            while (f != null && f.frameId != frameId) f = f.parent;
            if (f == null) return;
            // Close everything this exit is unwinding past, not just our own frame: a frame left
            // open by a skipped exit would otherwise be reported with no end time at all, which
            // reads as "still running" in a finished document.
            for (TraceFrame x = c.current; x != null && x != f; x = x.parent) closeOne(x);
            closeOne(f);
            c.current = f.parent;
            c.depth = f.depth;
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    private static void closeOne(TraceFrame f) {
        if (f.closed) return;
        f.closed = true;
        f.endNanos = System.nanoTime();
    }

    private static void onThrew(Throwable t, int frameId) {
        try {
            TraceContext.Cursor c = CURSOR.get();
            if (c == null) return;
            TraceFrame f = c.current;
            while (f != null && f.frameId != frameId) f = f.parent;
            if (f == null) return;
            // CLASS NAME ONLY. Never getMessage() -- OTel's semantic conventions warn on
            // exception.message and not on exception.type, and a message is where an
            // application prints the value that caused the failure.
            f.threwClass = t == null ? "null" : t.getClass().getName();
        } catch (Throwable x) {
            failOpen(x);
        }
    }

    // ---- observations ----

    private static void onObsRef(Object o, int obsId) {
        try {
            SiteRegistry.ObsSite site = SiteRegistry.obs(obsId);
            if (site == null) return;
            TraceFrame f = frameFor(site.frameId);
            if (f == null) return;
            record(f, site, Observations.ofRef(obsId, site.name, o));
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    private static void onObsInt(int v, int obsId) {
        try {
            SiteRegistry.ObsSite site = SiteRegistry.obs(obsId);
            if (site == null) return;
            TraceFrame f = frameFor(site.frameId);
            if (f == null) return;
            record(f, site, Observations.ofInt(obsId, site.name, site.typeDesc, v));
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    private static void onObsLong(long v, int obsId) {
        try {
            SiteRegistry.ObsSite site = SiteRegistry.obs(obsId);
            if (site == null) return;
            TraceFrame f = frameFor(site.frameId);
            if (f == null) return;
            record(f, site, Observations.ofLong(obsId, site.name, v));
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    private static void onObsDouble(double v, int obsId) {
        try {
            SiteRegistry.ObsSite site = SiteRegistry.obs(obsId);
            if (site == null) return;
            TraceFrame f = frameFor(site.frameId);
            if (f == null) return;
            record(f, site, Observations.ofDouble(obsId, site.name, v));
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    private static void record(TraceFrame f, SiteRegistry.ObsSite site, Observation o) {
        TraceContext.Cursor c = CURSOR.get();
        int cap = c == null ? 64 : c.ctx.maxObsPerFrame;
        if (f.obsCount() >= cap) {
            if (!f.truncated) {
                f.truncated = true;
                TraceHealth.OBS_TRUNCATED.incrementAndGet();
            }
            return;
        }
        switch (site.kind) {
            case SiteRegistry.OBS_PARAM_ENTRY: f.addEntry(o); break;
            case SiteRegistry.OBS_PARAM_EXIT:  f.addExit(o); break;
            default:                           f.returnObs = o; break;
        }
    }

    /**
     * The frame an observation belongs to, or null. The check is not paranoia: when a cap refused
     * the frame push, the cursor still points at the CALLER, and attributing this method's
     * parameters to its caller would be a silently wrong document.
     */
    private static TraceFrame frameFor(int frameId) {
        TraceContext.Cursor c = CURSOR.get();
        if (c == null) return null;
        TraceFrame f = c.current;
        return f != null && f.frameId == frameId ? f : null;
    }

    private static void onArm(int armId, long a, long b) {
        try {
            SiteRegistry.ArmSite site = SiteRegistry.arm(armId);
            if (site == null) return;
            TraceFrame f = frameFor(site.frameId);
            if (f == null) return;
            TraceContext.Cursor c = CURSOR.get();
            int cap = c == null ? 64 : c.ctx.maxArmsPerFrame;
            if (f.armCount() >= cap) {
                if (!f.truncated) {
                    f.truncated = true;
                    TraceHealth.ARMS_TRUNCATED.incrementAndGet();
                }
                return;
            }
            f.addArm(new TraceFrame.ArmRecord(armId, a, b));
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    // ---- the parallelStream window ----

    private static void onCommonPoolArm() {
        try {
            if (!commonPoolWindowEnabled) return;
            TraceContext.Cursor c = CURSOR.get();
            if (c == null) return;
            if (CP_ARMED.getAndIncrement() == 0) {
                // The window is a single global slot, so it is only unambiguous while exactly
                // one trace is in flight. Refusing is the only honest answer otherwise.
                if (ACTIVE != 1) {
                    TraceHealth.COMMON_POOL_WINDOW_REFUSED.incrementAndGet();
                    return;
                }
                CP_CTX = c.ctx;
                CP_PARENT = c.current;
            }
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    private static void onCommonPoolDisarm() {
        try {
            if (!commonPoolWindowEnabled) return;
            if (CURSOR.get() == null) return;
            if (CP_ARMED.decrementAndGet() <= 0) {
                CP_ARMED.set(0);
                CP_CTX = null;
                CP_PARENT = null;
            }
        } catch (Throwable t) {
            failOpen(t);
        }
    }

    /**
     * The narrow fallback: a probe on a {@code ForkJoinPool.commonPool()} worker, inside an open
     * window, joins the trace. Every condition is checked, and the cheap ones first — this whole
     * method is off the untraced path (it is only reached after the gate passed and the
     * thread-local came back null).
     */
    private static TraceContext.Cursor joinCommonPool() {
        if (!commonPoolWindowEnabled) return null;
        TraceContext ctx = CP_CTX;
        if (ctx == null) return null;
        Thread t = Thread.currentThread();
        if (!(t instanceof ForkJoinWorkerThread)) return null;
        if (((ForkJoinWorkerThread) t).getPool() != ForkJoinPool.commonPool()) return null;
        TraceContext.Cursor c = ctx.cursorAt(CP_PARENT, CP_PARENT == null ? 0 : CP_PARENT.depth + 1,
                TraceFrame.VIA_COMMON_POOL);
        CURSOR.set(c);
        ctx.joined(true);
        TraceHealth.COMMON_POOL_JOINS.incrementAndGet();
        // The worker keeps the cursor only for as long as the window is open; a worker that
        // outlives the window would otherwise attribute the next request's work to this trace.
        Propagation.registerCommonPoolWorker();
        return c;
    }

    /** Called by {@link Propagation} when the window closes, to release borrowed workers. */
    static void clearCursorIfCommonPool() {
        TraceContext.Cursor c = CURSOR.get();
        if (c != null && TraceFrame.VIA_COMMON_POOL.equals(c.via)) CURSOR.remove();
    }

    // ================= trace lifecycle (called by TraceGate / Propagation) =================

    /** @return the cursor for a newly started trace, or null when a cap refused it. */
    static TraceContext.Cursor beginTrace(TraceContext ctx) {
        synchronized (LOCK) {
            if (!enabled) return null;
            if (ACTIVE >= maxConcurrent) {
                TraceHealth.REJECTED_CONCURRENCY.incrementAndGet();
                return null;
            }
            ACTIVE++;
        }
        TraceContext.Cursor c = ctx.cursorAt(null, 0, TraceFrame.VIA_REQUEST);
        CURSOR.set(c);
        TraceHealth.TRACES_STARTED.incrementAndGet();
        return c;
    }

    static void endTrace(TraceContext ctx) {
        CURSOR.remove();
        CP_ARMED.set(0);
        if (CP_CTX == ctx) {
            CP_CTX = null;
            CP_PARENT = null;
        }
        // The latch FIRST, then the gate. Reversing these would leave a window in which a
        // borrowed worker sees ACTIVE == 0, skips the gate, and never learns the trace ended --
        // harmless -- but a worker that got past the gate a nanosecond earlier would be appending
        // to a tree already being serialised, which is not.
        ctx.finish();
        Propagation.releaseCommonPoolWorkers();
        synchronized (LOCK) {
            if (--ACTIVE < 0) ACTIVE = 0;
        }
        TraceHealth.TRACES_COMPLETED.incrementAndGet();
    }

    static TraceContext.Cursor cursor() { return CURSOR.get(); }

    /**
     * Enters the tracer's own reflective region. While it is entered, {@link #enter} pushes
     * nothing and every observation finds no frame, so the projection cannot observe itself.
     * Balanced by {@link #suppressEnd} in a {@code finally}.
     */
    static void suppressBegin() {
        TraceContext.Cursor c = CURSOR.get();
        if (c != null) c.suppress++;
    }

    static void suppressEnd() {
        TraceContext.Cursor c = CURSOR.get();
        if (c != null && c.suppress > 0) c.suppress--;
    }

    static void setCursor(TraceContext.Cursor c) {
        if (c == null) CURSOR.remove();
        else CURSOR.set(c);
    }

    static boolean propagateEnabled() { return propagate; }

    /**
     * Drain-thread safety valve, the same one {@code EdgeRuntime.reapStaleTraces} is: a non-zero
     * {@link #ACTIVE} with no trace in flight would make every probe in the JVM pay a
     * thread-local read for ever.
     *
     * @return true when the gate was actually reset.
     */
    public static boolean reapStaleTraces() {
        synchronized (LOCK) {
            if (ACTIVE == 0) return false;
            ACTIVE = 0;
            CP_CTX = null;
            CP_PARENT = null;
            CP_ARMED.set(0);
            return true;
        }
    }

    private static void failOpen(Throwable t) {
        try {
            failed = true;
            enabled = false;
            synchronized (LOCK) {
                ACTIVE = 0;
            }
            CP_CTX = null;
            CP_PARENT = null;
            TraceHealth.RUNTIME_FAILURES.incrementAndGet();
            if (TraceHealth.warnOnce("traceRuntimeFailed")) {
                TLog.warn("the per-request tracer hit " + t + " and is now OFF for the life of "
                        + "this JVM. The application and ax-agent's tiers are unaffected; "
                        + "health.runtimeFailures >= 1 and no further traces will be recorded.",
                        t);
            }
        } catch (Throwable ignored) {
            // nothing may escape into application code, not even from the failure path
        }
    }

    private TraceRuntime() { throw new AssertionError(); }
}
