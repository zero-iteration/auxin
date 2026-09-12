package ax.bench.g2;

/** Parent-loaded interface so the instrumented and uninstrumented chains are interchangeable. */
public interface ChainWork {
    int work(int x);
    long checksum();
}
