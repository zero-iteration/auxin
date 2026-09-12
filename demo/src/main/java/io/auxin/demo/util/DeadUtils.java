package io.auxin.demo.util;

import io.auxin.demo.model.Order;
import io.auxin.demo.service.LegacyReportService;

import java.util.List;

/**
 * GROUND TRUTH — NEVER LOADED.
 *
 * <p>Nothing in the application references this class, so the JVM never loads it. It is
 * the only reference to {@code LegacyReportService}, which is therefore never loaded
 * either — a two-hop dead subgraph.
 *
 * <p>Note what this class is NOT: it is not evidence that a static cascade is safe. The
 * cascade happens to be right here because the graph is tiny and reflection-free. On real
 * code, 61% of methods executed at runtime are missing from the static call graph, and
 * 79.7% of those misses are transitive consequences rather than missed entry points.
 */
public final class DeadUtils {

    private DeadUtils() {
    }

    public static String buildLegacyReport(List<Order> orders) {
        return new LegacyReportService().monthlyReport(orders);
    }

    public static String formatCents(int cents) {
        return String.format("%d.%02d", cents / 100, Math.abs(cents % 100));
    }
}
