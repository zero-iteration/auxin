package io.auxin.userapp;

/**
 * A customer class whose package shares a prefix with the agent's own (G5-BUG-1).
 *
 * <p>This is not a hypothetical. The shipped agent's ignore list contained
 * {@code "io/auxin/"} matched with {@code startsWith} and consulted before the include
 * scope, so on the G5 demo application ({@code io.auxin.demo.*}) it instrumented
 * <b>zero classes and reported a clean run</b> — {@code classesInstrumented: 0},
 * {@code classesSkipped: {}}, {@code transformFailures: 0}. Indistinguishable from "all of your
 * code is dead", which is the exact failure mode VALIDATION C37 exists to prevent. The same list
 * still carries {@code datadog/}, {@code com/newrelic/} and {@code com/dynatrace/}, every one of
 * which can prefix-match a customer's own packages.
 *
 * <p>So: an explicitly named {@code ax.include.packages} entry must win. This class is named by
 * the smoke run's include list and must be instrumented, while the agent's own
 * {@code io.auxin.agent.*} must not be — one prefix apart.
 */
public class Thing {

    private long calls;

    public int work(int n) {
        calls++;
        return n * 3 + 1;
    }

    /** Never called by the smoke app: its probe must stay unset. */
    public int idle(int n) {
        calls++;
        return n - 1;
    }

    public long calls() {
        return calls;
    }
}
