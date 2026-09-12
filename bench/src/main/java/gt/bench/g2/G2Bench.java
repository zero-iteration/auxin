package ax.bench.g2;

import ax.bench.gen.ProbeStyle;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * GATE G2 - end-to-end throughput delta on a small-method-heavy call graph.
 *
 * This is the number that matters, not an isolated probe microbenchmark: the cost of
 * instrumentation is dominated by what the probe does to the JIT's inlining decisions, and an
 * isolated probe benchmark cannot see that at all (VALIDATION A2).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
public class G2Bench {

    @State(Scope.Thread)
    public static class Seed { public int x = 0x2545F491; }

    public abstract static class Arm {
        public ChainWork engine;
        protected void init(ProbeStyle s) { engine = ChainInstrumenter.newEngine(s); }
    }

    @State(Scope.Benchmark) public static class Clean extends Arm {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.NONE); } }
    @State(Scope.Benchmark) public static class Blind extends Arm {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.BLIND); } }
    @State(Scope.Benchmark) public static class Read extends Arm {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.READ_STORE); } }

    @Benchmark
    public void b0_uninstrumented(Clean a, Seed s, Blackhole bh) {
        s.x = a.engine.work(s.x);
        bh.consume(s.x);
    }

    @Benchmark
    public void b1_blindStore(Blind a, Seed s, Blackhole bh) {
        s.x = a.engine.work(s.x);
        bh.consume(s.x);
    }

    @Benchmark
    public void b2_readThenStore(Read a, Seed s, Blackhole bh) {
        s.x = a.engine.work(s.x);
        bh.consume(s.x);
    }
}
