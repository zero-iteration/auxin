package ax.bench.g1;

/** Monomorphic call target for the G1 arms. One implementation is loaded per JMH fork. */
public interface Worker {
    int work(int x);
}
