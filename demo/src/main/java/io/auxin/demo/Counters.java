package io.auxin.demo;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Execution counters, printed as one JSON line at the end of the run.
 *
 * <p>This is how a harness asserts the fixture actually behaved: every rare branch
 * increments a counter here, so "did {@code FraudService.deepScan} run, and how often"
 * is answerable without instrumenting anything.
 *
 * <p>Note the counters are the app's own bookkeeping, not coverage data. They are the
 * independent oracle a coverage agent is checked against.
 */
public final class Counters {

    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private Counters() {
    }

    public static void inc(String name) {
        COUNTS.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public static long get(String name) {
        LongAdder a = COUNTS.get(name);
        return a == null ? 0L : a.sum();
    }

    public static String toJson() {
        StringBuilder sb = new StringBuilder(128).append('{');
        boolean first = true;
        for (Map.Entry<String, LongAdder> e : new TreeMap<>(COUNTS).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue().sum());
        }
        return sb.append('}').toString();
    }
}
