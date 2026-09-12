package io.auxin.demo.service;

import io.auxin.demo.model.Order;

import java.util.List;

/**
 * GROUND TRUTH — NEVER LOADED, but statically reachable.
 *
 * <p>The only reference to this class is inside {@code util.DeadUtils}, which is itself
 * never loaded. So a static call graph that starts from "every public method is an entry
 * point" reaches it, while the JVM never resolves the constant-pool entry and never loads
 * the class.
 *
 * <p>This pair (this class + {@code AuditRepository}, which nothing references at all) is
 * the fixture for: static reachability and runtime loading are independent signals, and
 * the verdict rule needs both.
 */
public final class LegacyReportService {

    public String monthlyReport(List<Order> orders) {
        StringBuilder sb = new StringBuilder();
        for (Order o : orders) {
            sb.append(o.toLegacyCsv()).append('\n');
        }
        return sb.toString();
    }

    public String quarterlyReport(List<Order> orders) {
        return monthlyReport(orders) + "\n-- quarter end --";
    }
}
