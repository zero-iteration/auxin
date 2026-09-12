package io.auxin.trace.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Context capture at SUBMIT, restore at RUN. The rule, and the list of things to wrap, is
 * OpenTelemetry's executors instrumentation — this is deliberately not hand-rolled semantics.
 *
 * <h3>What is taken from OTel's design, and what necessarily differs</h3>
 * <table>
 *   <tr><th></th><th>OTel</th><th>here</th></tr>
 *   <tr><td>when the context is captured</td><td>at submit</td><td>at submit — same</td></tr>
 *   <tr><td>where it is restored</td><td>around the task body, in a try/finally that always
 *       restores the previous context</td><td>same</td></tr>
 *   <tr><td>what is wrapped</td><td>{@code Executor.execute}, all {@code ExecutorService.submit}
 *       overloads, {@code invokeAll}/{@code invokeAny}, the {@code CompletableFuture} *Async
 *       family, {@code ForkJoinPool}, {@code ForkJoinTask}</td>
 *       <td>the same list minus {@code ForkJoinTask} — see "not handled" below</td></tr>
 *   <tr><td><b>where the wrapping is installed</b></td>
 *       <td>on the executor classes themselves, inside {@code java.util.concurrent}</td>
 *       <td><b>at the call site, in the instrumented application class</b></td></tr>
 * </table>
 *
 * <p>That last row is forced, not chosen. {@code java.util.concurrent} is defined by the
 * bootstrap loader, which cannot resolve {@code io.auxin.trace.*}; emitting a call to this class
 * from {@code ThreadPoolExecutor.execute} would be a {@code NoClassDefFoundError} inside the JDK.
 * ax-agent already established the shape of that problem and its limit: a constant can be
 * bridged through {@code java.lang.$Auxin}, a per-invocation CALL cannot (every
 * {@code equals}-based hop allocates an {@code Object[]}, so {@code tier2NotBridgeable} exists).
 * Rewriting the call site instead keeps every reference inside a class whose loader can see this
 * module. The cost is stated below and is real.
 *
 * <h3>Not handled, stated plainly</h3>
 * <ul>
 *   <li><b>A submission made from library code.</b> If the application calls into a framework
 *       and the framework submits to a pool, that submit site is not in
 *       {@code ax.trace.include.packages} and is not rewritten. The task's frames are recorded
 *       only if the framework happens to run them on the calling thread.</li>
 *   <li><b>A {@code ForkJoinTask} subclass submitted directly.</b> Wrapping by delegation needs
 *       an interface; {@code ForkJoinTask} is an abstract class with a protected {@code exec()},
 *       so there is nothing to wrap at the call site. Handling it needs
 *       {@code ForkJoinTask.doExec} instrumented in the JDK — bootstrap-visible — which this
 *       module does not do.</li>
 *   <li><b>{@code parallelStream()}.</b> Not handled here at all; see
 *       {@link TraceRuntime#commonPoolArm()} for the separate, labelled mechanism and its
 *       honest over-capture.</li>
 *   <li><b>A thread started directly</b> ({@code new Thread(r).start()}) is wrapped, because the
 *       {@code Thread} constructor takes a {@code Runnable} at an instrumented call site — but
 *       only when the call site is in scope.</li>
 * </ul>
 *
 * <h3>Cost on an untraced request</h3>
 * Zero. Every wrapper method in {@link TraceRuntime} returns its argument unchanged after one
 * static load and one branch, so the object identity handed to the executor is the one the
 * application created and nothing is allocated.
 */
public final class Propagation {

    /**
     * Common-pool workers that borrowed a cursor through the parallelStream window. Weakly held
     * and cleared when the window closes: a worker thread outlives the request by design, and a
     * left-behind cursor would attribute the NEXT request's work to a finished trace.
     */
    private static final Map<Thread, Boolean> BORROWED =
            Collections.synchronizedMap(new java.util.WeakHashMap<Thread, Boolean>());

    static void registerCommonPoolWorker() {
        BORROWED.put(Thread.currentThread(), Boolean.TRUE);
    }

    /** How many common-pool workers currently hold a borrowed cursor. Ops/test hook. */
    public static int borrowedWorkers() {
        synchronized (BORROWED) { return BORROWED.size(); }
    }

    /**
     * Called when the trace ends. We cannot run code on another thread to clear its
     * {@code ThreadLocal}, so a borrowed worker's cursor is invalidated by the CONTEXT instead:
     * {@link TraceContext#finish} latches the context closed and {@link TraceContext#push} then
     * refuses every further frame. That is the correctness step — without it a worker that had
     * already moved on would append to a document being serialised.
     *
     * <p>This thread's own cursor IS cleared directly, and the set is emptied so that a worker
     * reused by a later trace is counted as a new join.
     */
    static void releaseCommonPoolWorkers() {
        synchronized (BORROWED) {
            if (!BORROWED.isEmpty()) BORROWED.clear();
        }
        TraceRuntime.clearCursorIfCommonPool();
    }

    // ---------------- the carriers ----------------

    static Runnable wrap(Runnable r) {
        TraceContext.Cursor c = capture();
        if (c == null || r == null) return r;
        return new CarrierRunnable(c, r);
    }

    static <T> Callable<T> wrap(Callable<T> t) {
        TraceContext.Cursor c = capture();
        if (c == null || t == null) return t;
        return new CarrierCallable<T>(c, t);
    }

    static <T> Supplier<T> wrap(Supplier<T> s) {
        TraceContext.Cursor c = capture();
        if (c == null || s == null) return s;
        return new CarrierSupplier<T>(c, s);
    }

    static <T, R> Function<T, R> wrap(Function<T, R> f) {
        TraceContext.Cursor c = capture();
        if (c == null || f == null) return f;
        return new CarrierFunction<T, R>(c, f);
    }

    static <T> Consumer<T> wrap(Consumer<T> k) {
        TraceContext.Cursor c = capture();
        if (c == null || k == null) return k;
        return new CarrierConsumer<T>(c, k);
    }

    static <T, U> BiConsumer<T, U> wrap(BiConsumer<T, U> k) {
        TraceContext.Cursor c = capture();
        if (c == null || k == null) return k;
        return new CarrierBiConsumer<T, U>(c, k);
    }

    static <T, U, R> BiFunction<T, U, R> wrap(BiFunction<T, U, R> f) {
        TraceContext.Cursor c = capture();
        if (c == null || f == null) return f;
        return new CarrierBiFunction<T, U, R>(c, f);
    }

    @SuppressWarnings("unchecked")
    static Collection<?> wrapAll(Collection<?> tasks) {
        TraceContext.Cursor c = capture();
        if (c == null || tasks == null || tasks.isEmpty()) return tasks;
        List<Object> out = new ArrayList<Object>(tasks.size());
        for (Object t : tasks) {
            if (t instanceof Callable) out.add(wrap((Callable<Object>) t));
            else if (t instanceof Runnable) out.add(wrap((Runnable) t));
            else out.add(t);
        }
        return out;
    }

    /** The submit-side capture. Null when this thread is not inside a trace. */
    private static TraceContext.Cursor capture() {
        if (!TraceRuntime.propagateEnabled()) return null;
        TraceContext.Cursor c = TraceRuntime.cursor();
        if (c == null) return null;
        TraceHealth.CONTEXTS_PROPAGATED.incrementAndGet();
        return c;
    }

    /**
     * Restores on the worker thread and ALWAYS puts back whatever was there — a pool thread can
     * legitimately already be inside another trace's task, and clobbering that would corrupt two
     * documents instead of one.
     */
    private static TraceContext.Cursor enterTask(TraceContext.Cursor captured) {
        TraceContext.Cursor prev = TraceRuntime.cursor();
        // A fresh cursor per task, parented at the frame that submitted it: the task's frames
        // belong under the submit site in the tree, and two tasks from one submit site must not
        // share a mutable cursor.
        TraceContext.Cursor mine = captured.ctx.cursorAt(captured.current,
                captured.depth, TraceFrame.VIA_EXECUTOR);
        captured.ctx.joined(false);
        TraceRuntime.setCursor(mine);
        return prev;
    }

    private static void leaveTask(TraceContext.Cursor prev) {
        TraceRuntime.setCursor(prev);
    }

    static final class CarrierRunnable implements Runnable {
        private final TraceContext.Cursor c;
        private final Runnable d;

        CarrierRunnable(TraceContext.Cursor c, Runnable d) { this.c = c; this.d = d; }

        @Override public void run() {
            TraceContext.Cursor prev = enterTask(c);
            try {
                d.run();
            } finally {
                leaveTask(prev);
            }
        }

        /** So a debugger, a thread dump and {@code Thread.getName} still say something useful. */
        @Override public String toString() { return "ax-trace:" + d.getClass().getName(); }
    }

    static final class CarrierCallable<T> implements Callable<T> {
        private final TraceContext.Cursor c;
        private final Callable<T> d;

        CarrierCallable(TraceContext.Cursor c, Callable<T> d) { this.c = c; this.d = d; }

        @Override public T call() throws Exception {
            TraceContext.Cursor prev = enterTask(c);
            try {
                return d.call();
            } finally {
                leaveTask(prev);
            }
        }
    }

    static final class CarrierSupplier<T> implements Supplier<T> {
        private final TraceContext.Cursor c;
        private final Supplier<T> d;

        CarrierSupplier(TraceContext.Cursor c, Supplier<T> d) { this.c = c; this.d = d; }

        @Override public T get() {
            TraceContext.Cursor prev = enterTask(c);
            try {
                return d.get();
            } finally {
                leaveTask(prev);
            }
        }
    }

    static final class CarrierFunction<T, R> implements Function<T, R> {
        private final TraceContext.Cursor c;
        private final Function<T, R> d;

        CarrierFunction(TraceContext.Cursor c, Function<T, R> d) { this.c = c; this.d = d; }

        @Override public R apply(T t) {
            TraceContext.Cursor prev = enterTask(c);
            try {
                return d.apply(t);
            } finally {
                leaveTask(prev);
            }
        }
    }

    static final class CarrierConsumer<T> implements Consumer<T> {
        private final TraceContext.Cursor c;
        private final Consumer<T> d;

        CarrierConsumer(TraceContext.Cursor c, Consumer<T> d) { this.c = c; this.d = d; }

        @Override public void accept(T t) {
            TraceContext.Cursor prev = enterTask(c);
            try {
                d.accept(t);
            } finally {
                leaveTask(prev);
            }
        }
    }

    static final class CarrierBiConsumer<T, U> implements BiConsumer<T, U> {
        private final TraceContext.Cursor c;
        private final BiConsumer<T, U> d;

        CarrierBiConsumer(TraceContext.Cursor c, BiConsumer<T, U> d) { this.c = c; this.d = d; }

        @Override public void accept(T t, U u) {
            TraceContext.Cursor prev = enterTask(c);
            try {
                d.accept(t, u);
            } finally {
                leaveTask(prev);
            }
        }
    }

    static final class CarrierBiFunction<T, U, R> implements BiFunction<T, U, R> {
        private final TraceContext.Cursor c;
        private final BiFunction<T, U, R> d;

        CarrierBiFunction(TraceContext.Cursor c, BiFunction<T, U, R> d) { this.c = c; this.d = d; }

        @Override public R apply(T t, U u) {
            TraceContext.Cursor prev = enterTask(c);
            try {
                return d.apply(t, u);
            } finally {
                leaveTask(prev);
            }
        }
    }

    private Propagation() { throw new AssertionError(); }
}
