package io.auxin.demo.service;

import io.auxin.demo.Counters;
import io.auxin.demo.model.Order;

/**
 * Fraud checks.
 *
 * <p>The class itself is LOADED and partly LIVE (deepScan runs on a rare branch), while
 * {@link #flagSuspicious} is never invoked. "Class was executed" is therefore not a useful
 * granularity, and neither is "class was loaded" — only the method-level answer is right.
 */
public final class FraudService {

    /**
     * GROUND TRUTH — RARE. Executed only when {@code index % 500 == 0 && index > 0}.
     * Guaranteed at least once with the default {@code --min-requests=600}.
     */
    public boolean deepScan(Order order) {
        Counters.inc("rare.deepScan");
        int score = score(order);
        if (score > 90) {
            // NEVER TAKEN. Order amounts are 1000..50999 cents, so score() is bounded at
            // 50. The call site exists in the constant pool — a static call graph sees the
            // edge — but the branch never executes. See flagSuspicious below.
            flagSuspicious(order);
        }
        return score > 90;
    }

    /** Live, but only through {@link #deepScan}: rare-transitive, not directly called. */
    private int score(Order order) {
        Counters.inc("rare.score");
        return (order.amountCents() / 1000) % 100;
    }

    /**
     * GROUND TRUTH — DEAD. Reachable in one hop from a method that DOES execute, and still
     * never executed itself. A call-graph-based cascade that concluded "deepScan runs,
     * therefore its callees run" would get this wrong.
     */
    public void flagSuspicious(Order order) {
        Counters.inc("dead.flagSuspicious");
    }
}
