package ax.bench.g6;

/**
 * Monomorphic call target for the G6 arms. One implementation is loaded per JMH fork, exactly as
 * G1 does, so the {@code work()} call site stays monomorphic and no arm pollutes another arm's
 * inlining profile.
 */
public interface EdgeWorker {
    int work(int x);
}
