package ax.bench.g6;

import io.auxin.agent.runtime.EdgeRuntime;
import io.auxin.agent.runtime.Ring;

/**
 * G6 self-check. Runs BEFORE the JMH arms and exists to answer one question: is each arm
 * actually doing what its name says?
 *
 * <p>A benchmark of instrumentation that silently records nothing is a benchmark of nothing, and
 * it would report exactly the number we want to see. So every arm is run once here, the ring is
 * drained by hand, and the edge count is printed and checked against the arithmetic: 8 edges per
 * sampled root invocation, none at all when the tier is off, not sampled, or when the trace
 * belongs to another thread.
 */
public final class EdgeSelfCheck {

    private static final int OPS = 4096;
    private static final int NEVER = 1 << 30;

    private static int failures;

    public static void main(String[] args) {
        System.out.println("# G6 self-check: " + OPS + " ops, "
                + EdgeArms.LEAF_METHODS + " leaf methods per op");
        System.out.println();
        System.out.printf("%-26s %-12s %10s %10s%n", "arm", "rate", "edges", "expected");

        // The control and the default deployment: nothing may be recorded, ever.
        run("NONE (control)", EdgeStyle.NONE, false, 1, 0);
        run("EDGES, tier off", EdgeStyle.EDGES, false, 1024, 0);
        // The tier is ON; no root entry is sampled.
        run("EDGES, never sampled", EdgeStyle.EDGES, true, NEVER, 0);
        run("ROOT_ONLY, never sampled", EdgeStyle.ROOT_ONLY, true, NEVER, 0);
        // Every root entry sampled: 8 edges per op.
        run("EDGES, every root", EdgeStyle.EDGES, true, 1, OPS * EdgeArms.LEAF_METHODS);
        // 1-in-1024: exactly OPS/1024 traces, because the counter is per thread and exact.
        run("EDGES, 1-in-1024", EdgeStyle.EDGES, true, 1024,
                (OPS / 1024) * EdgeArms.LEAF_METHODS);
        // Another thread's trace holds the gate up; this thread has no trace, so no edges.
        runForeign();

        System.out.println();
        if (failures > 0) {
            System.out.println("== G6 SELF-CHECK FAILED: " + failures + " arm(s) ==");
            System.exit(1);
        }
        System.out.println("== G6 self-check passed: every arm records exactly what it claims ==");
    }

    private static void run(String label, EdgeStyle style, boolean enabled, int rate,
                            long expected) {
        Ring r = new Ring(1 << 20);
        EdgeRuntime.install(r, rate, 32, 256, enabled);
        EdgeWorker w = EdgeArms.newWorker(style);
        int x = 0x12345678;
        for (int i = 0; i < OPS; i++) x = w.work(x);
        report(label, rate, drain(r), expected, x);
    }

    private static void runForeign() {
        Ring r = new Ring(1 << 20);
        EdgeRuntime.install(r, 1, 32, 256, true);
        EdgeWorker w = EdgeArms.newWorker(EdgeStyle.CALLEES_ONLY);
        EdgeArms.holdTraceOpen(1 << 20);
        int x = 0x12345678;
        for (int i = 0; i < OPS; i++) x = w.work(x);
        report("CALLEES_ONLY, foreign trace", 1, drain(r), 0, x);
        if (EdgeRuntime.activeTraces() == 0) {
            System.out.println("  FAIL  the foreign trace was not open during the arm");
            failures++;
        }
    }

    private static long drain(Ring r) {
        final long[] n = new long[1];
        r.drain(new Ring.EventHandler() {
            @Override
            public void onEvent(long event) {
                n[0]++;
            }
        }, 1 << 24);
        return n[0];
    }

    private static void report(String label, int rate, long edges, long expected, int sink) {
        boolean ok = edges == expected;
        if (!ok) failures++;
        System.out.printf("%-26s %-12s %10d %10d  %s%n", label,
                rate == NEVER ? "never" : ("1-in-" + rate), edges, expected,
                ok ? "PASS" : "FAIL");
        if (sink == 42) System.out.println();     // keep the work observable
    }

    private EdgeSelfCheck() {}
}
