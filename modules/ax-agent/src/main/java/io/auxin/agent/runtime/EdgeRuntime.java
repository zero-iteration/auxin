package io.auxin.agent.runtime;

import io.auxin.agent.health.Health;
import io.auxin.agent.util.Log;

/**
 * The call-edge tier's application-thread path. Instrumented methods call exactly these four
 * methods and nothing else.
 *
 * <h3>The shape</h3>
 * <pre>
 *   root   (a tier-2 allowlisted boundary method):
 *       EdgeRuntime.rootEnter(id);
 *       try { ...body... EdgeRuntime.rootExit(id) at every return }
 *       catch (Throwable t) { EdgeRuntime.rootExit(id); throw t; }
 *
 *   callee (any other instrumented method):
 *       EdgeRuntime.enter(id);
 *       ...body... EdgeRuntime.exit(id) at every return
 * </pre>
 *
 * <h3>Why it is affordable (SCOPE-v3)</h3>
 * A caller stack costs a push and a pop on <b>every</b> method entry and exit, which is why
 * runtime edges were cut from PLAN-v2 in the first place. Sampling at the <i>root</i> — not per
 * call — is what buys it back: the decision is made once per entry into a boundary method, and
 * on an unsampled path every callee's {@code enter}/{@code exit} is
 *
 * <pre>   if (activeTraces == 0) return;   // one static load, one branch, nothing else</pre>
 *
 * no thread-local read, no stack push, no clock read, no allocation. {@link #activeTraces} is
 * zero whenever no thread anywhere is inside a sampled root invocation, which — at the default
 * 1-in-1024 — is almost always. When some other thread <i>is</i> tracing, the non-participating
 * threads degrade to one {@link ThreadLocal#get()} per call for the duration of that trace; the
 * benchmark measures both regimes.
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li><b>Zero allocation on the app hot path.</b> One {@link State} and one {@code int[]} per
 *       thread, allocated on that thread's first root entry and then reused for ever. Never
 *       grown. An edge is a packed {@code long} in a pre-allocated {@code long[]} ring
 *       (VALIDATION A7: {@code MpscArrayQueue<E>} forces an allocation, so it is not used).</li>
 *   <li><b>No aggregation here.</b> Counting happens on the drain thread
 *       ({@link EdgeAggregator}), exactly as tier-2 does (C29 / dd-trace's model).</li>
 *   <li><b>No clock.</b> An edge has no duration, so the A4 clocksource hazard — 25-27ns on
 *       tsc, <b>367ns on Xen</b> — is not on this path at all. That is deliberate: latency
 *       already belongs to tier-2, and paying for {@code nanoTime} twice per frame would make a
 *       sampled trace cost more than the request it is measuring.</li>
 *   <li><b>Bounded.</b> At most {@code ax.edges.max.depth} frames and
 *       {@code ax.edges.max.per.root} edges per sampled root invocation, both counted when they
 *       bite. Unbounded recursion therefore cannot fill the ring.</li>
 *   <li><b>Fail-open absolutely.</b> No {@code Throwable} reaches application code. Any surprise
 *       latches the tier off for the life of the JVM, counts it and logs once.</li>
 * </ul>
 *
 * <h3>Exception safety, and why only the root needs a handler</h3>
 * A root's {@code rootExit} runs from a {@code catch (Throwable)} handler, so a trace is always
 * closed and {@link #activeTraces} always balances — a leaked increment would make every
 * instrumented call in the JVM pay the thread-local read for ever, which is the one cost this
 * design cannot afford. A <i>callee</i> deliberately gets no handler: that keeps its
 * instrumentation to two call sites with no local, no branch and no stack map frame, so it can be
 * installed in any method of any class file version. An exception that skips a callee's
 * {@code exit} therefore leaves a stale frame on our stack, and {@link #popTo} repairs it — the
 * next exit or the root's own exit pops to the frame it names, whatever is above it.
 */
public final class EdgeRuntime {

    /**
     * THE GATE. Number of threads currently inside a sampled root invocation.
     *
     * <p>Mutated only under {@link #LOCK} (once per sampled trace, so 1-in-1024 root entries at
     * the default rate) and read on every instrumented call. {@code volatile} because a stuck
     * non-zero value is the only failure mode here that costs money for ever, and reasoning
     * about a plain field across a reaped or failed tier is not worth the one load: on the
     * x86_64 production target a volatile int read compiles to the same {@code mov} as a plain
     * one. On aarch64 it is an {@code ldar}, so the benchmark numbers from this machine are if
     * anything pessimistic.
     */
    private static volatile int activeTraces;

    /** Serialises trace start/end. Never taken on an unsampled path. */
    private static final Object LOCK = new Object();

    /** Tier kill switch. False by default: the edge tier is opt-in ({@code ax.edges.enabled}). */
    private static volatile boolean enabled;

    /** Latched by {@link #fail}: once off, off for the life of the JVM. */
    private static volatile boolean failed;

    private static volatile Ring ring;

    /**
     * Read on the sampled path only, written once at premain, and <b>deliberately not
     * volatile</b>: {@link #install} writes these three and then writes {@link #enabled}, which
     * is volatile, last. Every path that reads them has already read {@code enabled} (or
     * {@link #activeTraces}, which only becomes non-zero after a thread read {@code enabled}),
     * so safe publication is what makes a plain field correct here — the ordinary
     * volatile-flag-published-configuration idiom. Reordering the assignment of {@code enabled}
     * to anywhere but last in {@code install} would break it.
     */
    private static int sampleMask;
    private static int maxDepth;
    private static int maxEdgesPerRoot;

    private static final ThreadLocal<State> STATE = new ThreadLocal<State>();

    /**
     * Per-thread trace state. One per thread for the life of the thread; the only allocation the
     * edge tier ever does on an application thread, and it happens on that thread's first entry
     * into a root, never again.
     */
    static final class State {
        /** Root entries seen by THIS thread. The sampling counter; per-thread, so uncontended. */
        long roots;
        /** Caller stack: edge ids, innermost last. Allocated once, never grown. */
        final int[] stack;
        int depth;
        int edges;
        boolean sampling;
        boolean depthTruncated;
        boolean rootTruncated;

        State(int maxDepth) {
            this.stack = new int[maxDepth < 1 ? 1 : maxDepth];
        }
    }

    // ---------------- premain wiring ----------------

    /**
     * @param r          the edge ring, pre-allocated at premain (never allocated later)
     * @param sampleRate 1-in-N root entries; must be a power of two
     */
    public static void install(Ring r, int sampleRate, int depthCap, int perRootCap, boolean on) {
        ring = r;
        sampleMask = (sampleRate < 1 ? 1 : sampleRate) - 1;
        maxDepth = depthCap < 1 ? 1 : depthCap;
        maxEdgesPerRoot = perRootCap < 0 ? 0 : perRootCap;
        enabled = on && r != null && !failed;   // MUST be last: it publishes the four above
    }

    /** Tier kill switch. In-flight traces still close, so {@link #activeTraces} still balances. */
    public static void disable() { enabled = false; }

    /**
     * Break-glass / verification control: take the same path an unexpected {@code Throwable}
     * would take.
     *
     * <p>It exists because "fails open" is otherwise an untestable claim. Nothing inside this
     * class is supposed to be able to throw, so there is no way to observe the latch, the
     * counter and the WARN actually working — and an alert built on
     * {@code agentHealth.edgeTierFailures} that has never once fired is not an alert. This is
     * also the honest way to switch the tier off mid-flight in an incident: it latches, so a
     * later {@code install} cannot quietly turn it back on.
     */
    public static void forceFailOpen(String why) {
        fail(new IllegalStateException(why == null ? "forceFailOpen" : why));
    }

    public static boolean enabled() { return enabled; }

    public static boolean failedOff() { return failed; }

    public static int activeTraces() { return activeTraces; }

    public static Ring ring() { return ring; }

    public static int maxDepth() { return maxDepth; }

    public static int maxEdgesPerRoot() { return maxEdgesPerRoot; }

    /** 1-in-N root entries. */
    public static int sampleRate() { return sampleMask + 1; }

    /**
     * Drain-thread safety valve. If {@link #activeTraces} is non-zero but no edge has been
     * recorded for several drain cycles, a trace was leaked (a thread died between
     * {@code rootEnter} and {@code rootExit}) and every instrumented call in the JVM is paying
     * the thread-local read. Resetting the gate can at worst truncate one in-flight trace: the
     * owning thread's own {@code rootExit} then decrements past zero and is clamped, and its
     * {@link State} is repaired by {@link #onRootEnter} the next time it enters a root.
     *
     * @return true when the gate was actually reset.
     */
    public static boolean reapStaleTraces() {
        synchronized (LOCK) {
            if (activeTraces == 0) return false;
            activeTraces = 0;
            return true;
        }
    }

    // ---------------- application thread: the hot path ----------------

    /**
     * Callee entry. <b>The number that decides whether this tier ships.</b> One static load and
     * one branch when nothing is being traced; everything else lives behind the call to
     * {@link #onEnter} so that this method stays small enough to inline.
     */
    public static void enter(int calleeEdgeId) {
        if (activeTraces == 0) return;
        onEnter(calleeEdgeId);
    }

    /** Callee return. Same gate; see {@link #popTo} for what it does inside a trace. */
    public static void exit(int calleeEdgeId) {
        if (activeTraces == 0) return;
        onExit(calleeEdgeId);
    }

    /**
     * Entry into a tier-2 boundary method: the one place a sampling decision is made. Gated on
     * the tier switch rather than on {@link #activeTraces}, because this is the method that
     * makes the gate non-zero in the first place.
     */
    public static void rootEnter(int rootEdgeId) {
        if (!enabled) return;
        onRootEnter(rootEdgeId);
    }

    /**
     * Return from a boundary method, on both the normal and the exceptional path. Ends the trace
     * this invocation started, if it started one.
     */
    public static void rootExit(int rootEdgeId) {
        if (activeTraces == 0) return;
        onRootExit(rootEdgeId);
    }

    // ---------------- application thread: the sampled path ----------------

    private static void onEnter(int calleeEdgeId) {
        try {
            State s = STATE.get();
            if (s == null || !s.sampling) return;   // some other thread is tracing, not us
            record(s, calleeEdgeId);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void onExit(int calleeEdgeId) {
        try {
            State s = STATE.get();
            if (s == null || !s.sampling) return;
            popTo(s, calleeEdgeId);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void onRootEnter(int rootEdgeId) {
        try {
            State s = STATE.get();
            if (s == null) {
                s = new State(maxDepth);            // once per thread, ever
                STATE.set(s);
            }
            if (s.sampling) {
                if (activeTraces != 0) {
                    // A root called from inside a sampled trace is an ordinary callee: it gets
                    // an edge and a frame, and its rootExit will pop rather than end the trace.
                    record(s, rootEdgeId);
                    return;
                }
                // The gate was reset under us (reapStaleTraces, or fail()). This State is stale;
                // repair it here rather than letting the thread never sample again.
                s.sampling = false;
            }
            if ((++s.roots & sampleMask) != 0) return;   // not sampled: nothing at all happens

            s.depth = 0;
            s.edges = 0;
            s.depthTruncated = false;
            s.rootTruncated = false;
            s.stack[s.depth++] = rootEdgeId;
            s.sampling = true;
            synchronized (LOCK) {
                activeTraces++;
            }
            Health.EDGE_ROOTS_SAMPLED.increment();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void onRootExit(int rootEdgeId) {
        try {
            State s = STATE.get();
            if (s == null || !s.sampling) return;
            // i == 0  : this is the invocation that started the trace.
            // i == -1 : our frame is gone (past the depth cap, or already popped). Ending the
            //           trace is the only safe answer -- the alternative is a leaked increment.
            if (popTo(s, rootEdgeId) <= 0) endTrace(s);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Records the edge {@code caller -> callee} and pushes the callee.
     *
     * <p>An edge is emitted only when the frame below really is the immediate caller, i.e. only
     * below the depth cap. Past it we know we do not know, so nothing is recorded rather than
     * something wrong.
     */
    private static void record(State s, int calleeEdgeId) {
        // The depth cap is the ARRAY's length, not the static maxDepth: the array was sized from
        // maxDepth when this thread first entered a root, and reading the static here instead
        // would be an ArrayIndexOutOfBoundsException the moment the two disagreed. They can only
        // disagree if install() is called twice in one JVM, which premain never does and a
        // benchmark harness does all the time -- and "only the test can hit it" is not a reason
        // to leave an index unchecked on a path that must never throw into application code.
        if (s.depth < s.stack.length) {
            if (s.edges < maxEdgesPerRoot) {
                long e = EdgeEvents.pack(s.stack[s.depth - 1], calleeEdgeId);
                if (!ring.offer(e)) Health.EDGE_RING_DROPPED.increment();
                s.edges++;
            } else if (!s.rootTruncated) {
                // Counted once per root invocation, not once per dropped edge: this is the
                // per-root cap biting, and a per-call counter on a hot path is exactly what
                // C29 says belongs on the drain thread instead.
                s.rootTruncated = true;
                Health.EDGES_TRUNCATED_ROOT.increment();
            }
            s.stack[s.depth++] = calleeEdgeId;
        } else if (!s.depthTruncated) {
            s.depthTruncated = true;
            Health.EDGES_TRUNCATED_DEPTH.increment();
        }
    }

    /**
     * Pops to the topmost frame carrying {@code id}, discarding everything above it.
     *
     * <p>Restore semantics, not decrement semantics, and that is what makes a callee's missing
     * handler survivable: an exception that unwound three frames without running their exits is
     * repaired by the first exit that does run. A frame that was never pushed (past the depth
     * cap) is simply not found, and then nothing moves.
     *
     * @return the index the frame was found at, or -1.
     */
    private static int popTo(State s, int id) {
        final int[] stack = s.stack;
        for (int i = s.depth - 1; i >= 0; i--) {
            if (stack[i] == id) {
                s.depth = i;
                return i;
            }
        }
        return -1;
    }

    private static void endTrace(State s) {
        s.sampling = false;
        s.depth = 0;
        synchronized (LOCK) {
            // Clamped because reapStaleTraces() may have zeroed the gate while this trace ran.
            if (--activeTraces < 0) activeTraces = 0;
        }
    }

    /**
     * Fail open, once and for all. The edge tier is a convenience; the application is not.
     * Anything unexpected turns the whole tier off for the life of the JVM, is counted, and is
     * said exactly once.
     */
    private static void fail(Throwable t) {
        try {
            failed = true;
            enabled = false;
            synchronized (LOCK) {
                activeTraces = 0;
            }
            Health.edgeTierFailure();
            if (Health.warnOnce("edgeTierFailed")) {
                Log.warn("the runtime call-edge tier hit " + t + " and is now OFF for the life of "
                        + "this JVM. Coverage, tier-2 and the strip are unaffected; the wire will "
                        + "carry agentHealth.edgeTierFailures >= 1 and no further edges.", t);
            }
        } catch (Throwable ignored) {
            // nothing may escape into application code, not even from the failure path
        }
    }

    private EdgeRuntime() { throw new AssertionError(); }
}
