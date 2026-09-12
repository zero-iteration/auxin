package io.auxin.trace.bench;

import io.auxin.trace.runtime.RateCap;
import io.auxin.trace.runtime.SiteRegistry;
import io.auxin.trace.runtime.TraceContext;
import io.auxin.trace.runtime.TraceGate;
import io.auxin.trace.runtime.TraceRuntime;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * THE GATE. What a request that is NOT being traced pays.
 *
 * <p>This is the one number that decides whether this module can ship. The claim under test is
 * the one in TRADE-OFFS.md: <b>one static load and one branch per probe site, no allocation.</b>
 * The arms call the real shipped {@code io.auxin.trace.runtime.TraceRuntime} out of
 * {@code ../target/ax-trace.jar}, so HotSpot's inlining decision about the shipped code is what
 * is measured — not a decision about a copy of it in this module.
 *
 * <h3>The arms</h3>
 * <table>
 *   <tr><td>{@code baseline}</td><td>the work, uninstrumented</td></tr>
 *   <tr><td>{@code enterExit}</td><td>+ {@code enter}/{@code exit}: the minimum a traced method
 *       carries. 2 probe sites.</td></tr>
 *   <tr><td>{@code fullProbeSet}</td><td>+ two parameter observations, one branch arm and one
 *       return observation: what a real 2-argument method with one predicate carries.
 *       6 probe sites.</td></tr>
 *   <tr><td>{@code wrapUntraced}</td><td>the executor call-site wrapper on the untraced path,
 *       which must return its argument unchanged and allocate nothing.</td></tr>
 *   <tr><td>{@code fullProbeSetWhileAnotherThreadTraces}</td><td><b>the regime that is NOT
 *       free.</b> One trace is in flight on another thread, so the gate passes and every probe
 *       falls through to a {@code ThreadLocal.get()} that finds nothing. Measured because the
 *       honest cost of this design is a cheap common case and an expensive rare one, and
 *       claiming only the first would be a lie.</td></tr>
 * </table>
 *
 * <h3>Reading it</h3>
 * Per-site cost is {@code (arm - baseline) / sites}. Run with {@code -prof gc} and check
 * {@code gc.alloc.rate.norm} is 0 B/op on every untraced arm: that is the zero-allocation claim,
 * and it is a harder number to fudge than the latency.
 *
 * <p><b>Hazard, the same one docs/TOOLCHAIN.md #2 states for ax-agent's gates:</b> this runs on
 * aarch64 (Apple silicon). A volatile int read is an {@code ldar} here and a plain {@code mov} on
 * x86_64, so these numbers are if anything pessimistic — but they are provisional until re-run on
 * x86_64 Linux.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-XX:+UseParallelGC"})
@State(Scope.Benchmark)
public class UntracedPathBenchmark {

    /** Registered site ids. Real ones, from the real registry, exactly as the emitter assigns. */
    private int frameId;
    private int obsA;
    private int obsB;
    private int obsRet;
    private int armId;

    private List<String> list;
    private String text;
    private Runnable task;

    /** Holds one trace open on a foreign thread for the contended arm. */
    private Thread holder;
    private final AtomicBoolean holding = new AtomicBoolean();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        frameId = SiteRegistry.registerFrame("bench.Subject", "work", "(Ljava/util/List;I)I",
                42, false);
        obsA = SiteRegistry.registerObs(frameId, SiteRegistry.OBS_PARAM_ENTRY, 0,
                "Ljava/util/List;", "arg0");
        obsB = SiteRegistry.registerObs(frameId, SiteRegistry.OBS_PARAM_ENTRY, 1, "I", "arg1");
        obsRet = SiteRegistry.registerObs(frameId, SiteRegistry.OBS_RETURN, -1, "I", "return");
        // 153 == Opcodes.IFEQ. The literal, not the constant: ASM is SHADED into the agent jar
        // as io.auxin.trace.shaded.asm, so org.objectweb.asm.Opcodes does not exist on this
        // classpath -- and the bench must run against the shipped jar, not a reconstruction.
        armId = SiteRegistry.registerArm(frameId, 153, 47, "call");

        list = new ArrayList<String>();
        for (int i = 0; i < 16; i++) list.add("item" + i);
        text = "hello";
        task = new Runnable() {
            @Override public void run() { }
        };

        // ARMED, exactly as in production: the runtime and the gate installed, and the master
        // switch on. The point of the untraced arms is that this costs nothing anyway.
        TraceRuntime.install(1, true, true, true);
        TraceGate.install("X-Auxin-Trace", "", new RateCap(1000000), new TraceGate.TraceSink() {
            @Override public void submit(TraceContext ctx) { }
        }, false, 1 << 20, 256, 64, 64, true);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        stopHolder();
    }

    // ================= the untraced arms =================

    /** The work, uninstrumented. Everything else is measured against this. */
    @Benchmark
    public int baseline() {
        return work(list, 7);
    }

    /** 2 probe sites: what the minimum instrumented method carries. */
    @Benchmark
    public int enterExit() {
        TraceRuntime.enter(frameId);
        int r = work(list, 7);
        TraceRuntime.exit(frameId);
        return r;
    }

    /**
     * 6 probe sites: entry, two parameter observations, one branch arm, the return observation
     * and the exit. This is what a real two-argument method with one predicate looks like after
     * the emitter has been over it, in the order the emitter emits them.
     */
    @Benchmark
    public int fullProbeSet() {
        TraceRuntime.enter(frameId);
        TraceRuntime.obsRef(list, obsA);
        TraceRuntime.obsInt(7, obsB);
        int r = work(list, 7);
        TraceRuntime.armI(r, armId);
        TraceRuntime.obsInt(r, obsRet);
        TraceRuntime.exit(frameId);
        return r;
    }

    /** The executor call-site wrapper: must hand back the same object and allocate nothing. */
    @Benchmark
    public Runnable wrapUntraced() {
        return TraceRuntime.wrapRunnable(task);
    }

    /** A reference observation on a String: the shape that must record LENGTH and never content. */
    @Benchmark
    public void obsRefOnly(Blackhole bh) {
        TraceRuntime.obsRef(text, obsA);
        bh.consume(text);
    }

    // ================= the contended arm =================

    /**
     * THE REGIME THAT IS NOT FREE. A trace is open on another thread, so {@code ACTIVE != 0},
     * the gate passes, and each of the six probes does a {@link ThreadLocal#get()} that comes
     * back null. With the default rate cap (5 traces a minute) this window is a few milliseconds
     * per minute; a deployment that traces continuously sits here instead.
     */
    @Benchmark
    public int fullProbeSetWhileAnotherThreadTraces() throws Exception {
        startHolder();
        TraceRuntime.enter(frameId);
        TraceRuntime.obsRef(list, obsA);
        TraceRuntime.obsInt(7, obsB);
        int r = work(list, 7);
        TraceRuntime.armI(r, armId);
        TraceRuntime.obsInt(r, obsRet);
        TraceRuntime.exit(frameId);
        return r;
    }

    // ================= the subject =================

    /**
     * Small enough to be inlined and to make the probes visible, and not constant-foldable.
     * Deliberately NOT trivial: a method the emitter would classify as a trivial accessor is
     * never instrumented, so benchmarking one would measure a shape that never ships.
     */
    private static int work(List<String> xs, int n) {
        int acc = n;
        for (int i = 0, m = xs.size(); i < m; i++) acc += xs.get(i).length();
        return acc;
    }

    // ================= the foreign-trace holder =================

    /** A minimal stand-in for a ServletRequest: public class, public no-arg-ish accessor. */
    public static final class BenchRequest {
        public String getHeader(String name) { return "1"; }
    }

    private void startHolder() throws Exception {
        if (holding.get()) return;
        synchronized (holding) {
            if (holding.get()) return;
            final CountDownLatch started = new CountDownLatch(1);
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    // A request object the gate can read a header from, without the servlet
                    // API. A named PUBLIC class, not an anonymous one: TraceGate looks the
                    // accessor up with Class#getMethod and invokes it reflectively, and a
                    // package-private anonymous class would fail that with IllegalAccessException
                    // -- which would leave activeTraces at 0 and make this arm silently measure
                    // the uncontended path. The check below exists for the same reason.
                    TraceGate.begin(new BenchRequest());
                    started.countDown();
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    TraceGate.end();
                }
            }, "ax-trace-bench-holder");
            t.setDaemon(true);
            t.start();
            started.await(5, TimeUnit.SECONDS);
            if (TraceRuntime.activeTraces() == 0) {
                throw new IllegalStateException("the holder thread did not open a trace, so the "
                        + "contended arm would silently measure the UNCONTENDED path. "
                        + "activeTraces=0");
            }
            holder = t;
            holding.set(true);
        }
    }

    private void stopHolder() {
        Thread t = holder;
        if (t != null) t.interrupt();
        holder = null;
        holding.set(false);
    }
}
