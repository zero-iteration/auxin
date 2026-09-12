package ax.bench.g6;

/** What the G6 subject classes carry. */
public enum EdgeStyle {
    /** The control arm: no edge instrumentation anywhere. */
    NONE,
    /**
     * The shipped shape: {@code rootEnter}/{@code rootExit} plus a {@code catch (Throwable)} on
     * {@code work()}, and {@code enter}/{@code exit} on every leaf method.
     */
    EDGES,
    /**
     * Leaves instrumented, {@code work()} left alone. Used for the "another thread is tracing"
     * arm, where the measured threads must never enter a root of their own.
     */
    CALLEES_ONLY,
    /**
     * {@code work()} instrumented, leaves left alone. Subtracting this from {@link #EDGES}
     * isolates the per-callee cost from the root's once-per-invocation sampling decision.
     */
    ROOT_ONLY;

    public boolean root() { return this == EDGES || this == ROOT_ONLY; }

    public boolean callees() { return this == EDGES || this == CALLEES_ONLY; }
}
