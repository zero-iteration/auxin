package io.auxin.agent.instrument;

import io.auxin.agent.health.Health;
import io.auxin.agent.util.Log;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Startup CPU budget and circuit breaker (C15). A11 is the incident vector: a custom ByteBuddy
 * agent took a 30s startup to 10 minutes, and at 0.896 CPU a throttled pod misses the 1s
 * liveness probe default and CrashLoopBackOffs in a way that is "mathematically unlikely to
 * recover".
 *
 * <p>On breach: stop transforming, mark degraded, count it, and <b>never throw</b>. A JVM that
 * starts with no coverage is a bad window; a JVM that does not start is an outage.
 */
public final class StartupBudget {

    private final ThreadMXBean threads;
    private final boolean cpuSupported;
    private final long cpuBudgetNs;
    private final long wallBudgetNs;

    private final AtomicLong cumulativeCpuNs = new AtomicLong();
    private final AtomicLong cumulativeWallNs = new AtomicLong();
    private final AtomicBoolean breached = new AtomicBoolean();
    private final AtomicBoolean logged = new AtomicBoolean();

    public StartupBudget(long cpuBudgetMs, long wallBudgetMs) {
        ThreadMXBean t = null;
        boolean supported = false;
        try {
            t = ManagementFactory.getThreadMXBean();
            supported = t.isCurrentThreadCpuTimeSupported() && t.isThreadCpuTimeEnabled();
        } catch (Throwable ignored) {
            // some JVMs / security managers: fall back to wall clock only
        }
        this.threads = t;
        this.cpuSupported = supported;
        this.cpuBudgetNs = cpuBudgetMs * 1000000L;
        this.wallBudgetNs = wallBudgetMs * 1000000L;
    }

    public boolean breached() { return breached.get(); }

    public long cumulativeCpuMs() { return cumulativeCpuNs.get() / 1000000L; }

    public long cumulativeWallMs() { return cumulativeWallNs.get() / 1000000L; }

    public boolean cpuSupported() { return cpuSupported; }

    /** @return an opaque token for {@link #end}. */
    public long start() {
        long cpu = cpuSupported ? safeCpu() : 0L;
        return (cpu << 1) | 1L; // never 0
    }

    public void end(long token, long wallStartNs) {
        long wall = System.nanoTime() - wallStartNs;
        long totalWall = cumulativeWallNs.addAndGet(wall);
        long totalCpu = 0;
        if (cpuSupported) {
            long before = token >>> 1;
            long cpu = safeCpu() - before;
            if (cpu > 0) totalCpu = cumulativeCpuNs.addAndGet(cpu);
            else totalCpu = cumulativeCpuNs.get();
        }
        if ((cpuSupported && totalCpu > cpuBudgetNs) || totalWall > wallBudgetNs) {
            trip(cpuSupported && totalCpu > cpuBudgetNs ? "transformCpuBudget" : "transformWallBudget",
                    totalCpu, totalWall);
        }
    }

    private void trip(String reason, long cpuNs, long wallNs) {
        breached.set(true);
        Health.degrade(reason);
        if (logged.compareAndSet(false, true)) {
            Log.warn("startup budget breached (" + reason + "): cumulative transform cpu="
                    + (cpuNs / 1000000L) + "ms wall=" + (wallNs / 1000000L)
                    + "ms. Instrumentation STOPPED for the rest of this JVM's life; every window "
                    + "from here is marked degraded and must not be used as evidence of death.");
        }
    }

    private long safeCpu() {
        try {
            long v = threads.getCurrentThreadCpuTime();
            return v < 0 ? 0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }
}
