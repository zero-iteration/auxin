package ax.bench.g1;

import ax.bench.gen.ProbeStyle;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * GATE G1 - probe pattern shootout.
 *
 * Three mandated arms plus one bonus arm:
 *   (a) BLIND               LDC condy; CHECKCAST [Z; push idx; ICONST_1; BASTORE  (no branch, no frame)
 *   (b) READ_STORE          if (!p[idx]) p[idx] = true;                           (frame PER PROBE SITE)
 *   (c) READ_STORE_HOISTED  array hoisted into a local once per method
 *   (bonus) BLIND_HOISTED   what JaCoCo's ProbeInserter actually emits
 *
 * State is Scope.Benchmark on purpose: every thread stores into the SAME boolean[], the same few
 * bytes, i.e. the same cache line. That is the contention case, and it is the one that matters.
 * Run with -t 1, -t 4 and -t 10 (>= #available-cores on this box).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-XX:+UseParallelGC"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
public class ProbeBench {

    @State(Scope.Thread)
    public static class Seed {
        public int x = 0x12345678;
    }

    public abstract static class ArmState {
        public Worker w;
        protected void init(ProbeStyle style) { w = Arms.newWorker(style); }
    }

    @State(Scope.Benchmark) public static class SNone extends ArmState {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.NONE); } }
    @State(Scope.Benchmark) public static class SBlind extends ArmState {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.BLIND); } }
    @State(Scope.Benchmark) public static class SRead extends ArmState {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.READ_STORE); } }
    @State(Scope.Benchmark) public static class SReadHoist extends ArmState {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.READ_STORE_HOISTED); } }
    @State(Scope.Benchmark) public static class SBlindHoist extends ArmState {
        @Setup(Level.Trial) public void s() { init(ProbeStyle.BLIND_HOISTED); } }

    @Benchmark
    public void a0_baseline(SNone s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a1_blindStore(SBlind s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a2_readThenStore(SRead s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a3_readThenStoreHoisted(SReadHoist s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a4_blindStoreHoisted(SBlindHoist s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }
}
