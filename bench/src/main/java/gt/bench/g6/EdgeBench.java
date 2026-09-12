package ax.bench.g6;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * GATE G6 — the sampled runtime call-edge tier (SCOPE-v3).
 *
 * <p>One op = one invocation of {@code work()}, which is a sampling <b>root</b>, calling 8 small
 * leaf methods. That is 9 instrumented methods and 18 call sites per op, at Tier-1's method
 * granularity — the same shape G1 used, so the two gates' numbers are comparable.
 *
 * <p><b>The arm that decides whether this ships is {@link #a2_unsampled}.</b> Sampling is decided
 * once per root entry, so on an unsampled invocation every callee's {@code enter} and
 * {@code exit} must be one static load and one branch and nothing else: no thread-local read, no
 * stack push, no clock read, no allocation. Subtracting {@link #a3_rootOnlyUnsampled} separates
 * the root's once-per-invocation sampling decision from the per-call cost.
 *
 * <p>Run at 1, 4 and >= #cores threads: the gate is a shared static, and a shared static that is
 * written (on a sampled trace's start and end) is a false-sharing question, which is exactly what
 * G1 found does not transfer between architectures. Add {@code -prof gc} to see that the sampled
 * path allocates 0 B/op.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-XX:+UseParallelGC"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
public class EdgeBench {

    /** Never sampled: {@code (++n & (2^30 - 1)) == 0} does not fire inside a trial. */
    private static final int NEVER = 1 << 30;

    @State(Scope.Thread)
    public static class Seed {
        public int x = 0x12345678;
    }

    public abstract static class ArmState {
        public EdgeWorker w;

        protected void init(EdgeStyle style) {
            w = EdgeArms.newWorker(style);
        }

        @TearDown(Level.Trial)
        public void report() {
            System.out.println("# G6 " + getClass().getSimpleName() + ": " + EdgeArms.teardown());
        }
    }

    /** Control: no edge instrumentation at all. */
    @State(Scope.Benchmark)
    public static class SNone extends ArmState {
        @Setup(Level.Trial) public void s() { EdgeArms.installOff(); init(EdgeStyle.NONE); }
    }

    /** The default deployment: the calls are in the bytecode, the tier is off. */
    @State(Scope.Benchmark)
    public static class SOff extends ArmState {
        @Setup(Level.Trial) public void s() { EdgeArms.installOff(); init(EdgeStyle.EDGES); }
    }

    /** The tier is ON and this invocation is not sampled. THE number. */
    @State(Scope.Benchmark)
    public static class SUnsampled extends ArmState {
        @Setup(Level.Trial) public void s() { EdgeArms.install(true, NEVER); init(EdgeStyle.EDGES); }
    }

    /** Root instrumented, leaves not: isolates the sampling decision from the per-call cost. */
    @State(Scope.Benchmark)
    public static class SRootOnly extends ArmState {
        @Setup(Level.Trial) public void s() { EdgeArms.install(true, NEVER); init(EdgeStyle.ROOT_ONLY); }
    }

    /** Every root entry traced: the cost of actually recording a call graph. */
    @State(Scope.Benchmark)
    public static class SSampled extends ArmState {
        @Setup(Level.Trial) public void s() { EdgeArms.install(true, 1); init(EdgeStyle.EDGES); }
    }

    /** The production default, 1-in-1024 root entries. */
    @State(Scope.Benchmark)
    public static class SDefault extends ArmState {
        @Setup(Level.Trial) public void s() { EdgeArms.install(true, 1024); init(EdgeStyle.EDGES); }
    }

    /**
     * The degraded regime: another thread holds a sampled trace open, so this thread's gate is
     * non-zero and every call falls through to a thread-local read that finds nothing.
     */
    @State(Scope.Benchmark)
    public static class SForeign extends ArmState {
        @Setup(Level.Trial) public void s() {
            EdgeArms.install(true, 1);
            init(EdgeStyle.CALLEES_ONLY);
            EdgeArms.holdTraceOpen(1 << 20);
        }
    }

    @Benchmark
    public void a0_baseline(SNone s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a1_tierOff(SOff s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a2_unsampled(SUnsampled s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a3_rootOnlyUnsampled(SRootOnly s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a4_sampledEveryRoot(SSampled s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a5_default1in1024(SDefault s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }

    @Benchmark
    public void a6_foreignTraceOpen(SForeign s, Seed seed, Blackhole bh) {
        seed.x = s.w.work(seed.x);
        bh.consume(seed.x);
    }
}
