package ax.bench.g6;

import ax.bench.gen.SimpleLoader;
import io.auxin.agent.runtime.EdgeRuntime;
import io.auxin.agent.runtime.Ring;

import java.util.Collections;
import java.util.Map;

/**
 * Loads one G6 arm and installs the real agent runtime behind it.
 *
 * <p>No {@code -javaagent} is involved: the arms are generated with the instruction sequence
 * {@code ProbeEmitter} emits and {@link EdgeRuntime} is installed directly, which is what lets
 * one JMH fork hold exactly one configuration of the tier.
 */
public final class EdgeArms {

    /** work() plus 8 small leaf methods: 9 instrumented methods, 18 call sites, per op. */
    public static final int LEAF_METHODS = 8;
    public static final int INSTRUMENTED_METHODS_PER_OP = LEAF_METHODS + 1;

    /**
     * A big ring and a real drain, because a FULL ring is cheaper to offer to than an empty one
     * (one compare and a counter, no CAS, no store) and would flatter the sampled arms. The
     * drop count is printed at the end of every trial so a saturated measurement cannot be
     * mistaken for a fast one.
     */
    private static final int RING_CAPACITY = 1 << 20;

    /**
     * The drain parks when the ring is empty instead of spinning.
     *
     * <p>Not a detail: at 10 measured threads on 10 logical cores, a spinning harness thread is
     * an eleventh runnable thread, and the first version of this gate measured its own
     * scheduling noise as the cost of the unsampled path. Parking makes an idle drain free, and
     * the batch limit keeps it able to absorb ~65M events per second when the arm is producing.
     */
    private static final long IDLE_PARK_NANOS = 1000000L;

    private static volatile Ring ring;
    private static volatile Thread drain;
    private static volatile boolean draining;
    private static final long[] DRAINED = new long[1];

    private EdgeArms() {}

    public static String binaryName(EdgeStyle style) {
        return "ax.bench.g6.gen.W_" + style.name();
    }

    public static EdgeWorker newWorker(EdgeStyle style) {
        return newWorker(style, LEAF_METHODS, 0);
    }

    public static EdgeWorker newWorker(EdgeStyle style, int leafMethods, int firstEdgeId) {
        try {
            String bin = binaryName(style);
            Map<String, byte[]> defs = Collections.singletonMap(bin,
                    EdgeWorkerGenerator.generate(bin.replace('.', '/'), style, leafMethods,
                            firstEdgeId));
            ClassLoader cl = new SimpleLoader(EdgeWorker.class.getClassLoader(), defs, false);
            return (EdgeWorker) cl.loadClass(bin).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("cannot load arm " + style, e);
        }
    }

    /**
     * Arms the tier for this fork.
     *
     * @param sampleRate 1 = every root entry, 1024 = the production default,
     *                   {@code 1 << 30} = never, which is the unsampled arm
     */
    public static void install(boolean enabled, int sampleRate) {
        Ring r = new Ring(RING_CAPACITY);
        ring = r;
        EdgeRuntime.install(r, sampleRate, 32, 256, enabled);
        startDrain(r);
    }

    /** Off, but with the instrumentation still in the bytecode: the default deployment. */
    public static void installOff() {
        install(false, 1024);
    }

    /**
     * Holds one sampled trace open on a parked thread for the rest of the fork, so the global
     * gate is non-zero while the measured threads run.
     *
     * <p>This is the <b>degraded regime</b>, and it is measured rather than argued about: while
     * any thread anywhere is inside a sampled root, every other thread's {@code enter} and
     * {@code exit} fall through the gate into a {@link ThreadLocal} read that finds nothing.
     * Holding it open deterministically (instead of hammering a root from another thread) keeps
     * the measurement about the gate rather than about a competing core.
     */
    public static void holdTraceOpen(final int rootEdgeId) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                EdgeRuntime.rootEnter(rootEdgeId);      // sampled at rate 1, never exited
                while (true) {
                    try {
                        Thread.sleep(3600000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "g6-foreign-trace");
        t.setDaemon(true);
        t.start();
        for (int i = 0; i < 1000 && EdgeRuntime.activeTraces() == 0; i++) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (EdgeRuntime.activeTraces() == 0) {
            throw new IllegalStateException("could not hold an edge trace open; the arm would "
                    + "silently measure the fast path instead of the degraded one");
        }
    }

    private static void startDrain(final Ring r) {
        draining = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final Ring.EventHandler counter = new Ring.EventHandler() {
                    @Override
                    public void onEvent(long event) {
                        DRAINED[0]++;
                    }
                };
                final int limit = 1 << 16;
                while (draining) {
                    // Keep going while the ring has work; park when it does not, so an idle
                    // drain is not a runnable thread competing with the measured ones.
                    if (r.drain(counter, limit) < limit) {
                        java.util.concurrent.locks.LockSupport.parkNanos(IDLE_PARK_NANOS);
                    }
                }
            }
        }, "g6-drain");
        t.setDaemon(true);
        drain = t;
        t.start();
    }

    /**
     * Stops the drain and reports what the ring did. A non-zero drop count means the producer
     * side was measured against a full ring for part of the trial, i.e. the sampled number is
     * optimistic and must be reported as such.
     */
    public static String teardown() {
        draining = false;
        Thread t = drain;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Ring r = ring;
        if (r == null) return "ring=none";
        return "edges drained=" + DRAINED[0]
                + " droppedFull=" + r.droppedFull()
                + " droppedContended=" + r.droppedContended()
                + " activeTraces=" + EdgeRuntime.activeTraces();
    }
}
