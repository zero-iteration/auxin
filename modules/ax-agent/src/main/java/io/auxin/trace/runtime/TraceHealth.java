package io.auxin.trace.runtime;

import io.auxin.trace.util.TJson;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters, and the one-shot WARN latch. Everything here is read by the trace-builder thread and
 * written from anywhere, so every counter is an {@link AtomicLong} — none of them is on the
 * untraced path, which is the only path where an atomic would matter.
 *
 * <p>The rule from PLAN-v2 applies unchanged: a tracer that records nothing must be
 * distinguishable from a service that was never asked to trace. So every refusal is counted and
 * named, and the reasons are on the wire.
 */
public final class TraceHealth {

    public static final AtomicLong CLASSES_INSTRUMENTED = new AtomicLong();
    public static final AtomicLong METHODS_INSTRUMENTED = new AtomicLong();
    public static final AtomicLong ENTRIES_INSTRUMENTED = new AtomicLong();
    public static final AtomicLong CALL_SITES_WRAPPED = new AtomicLong();
    public static final AtomicLong TRANSFORM_FAILURES = new AtomicLong();

    public static final AtomicLong REQUESTS_SEEN = new AtomicLong();
    public static final AtomicLong TRACES_STARTED = new AtomicLong();
    public static final AtomicLong TRACES_COMPLETED = new AtomicLong();
    public static final AtomicLong REJECTED_NO_HEADER = new AtomicLong();
    public static final AtomicLong REJECTED_AUTH = new AtomicLong();
    public static final AtomicLong REJECTED_RATE_CAP = new AtomicLong();
    public static final AtomicLong REJECTED_CONCURRENCY = new AtomicLong();

    public static final AtomicLong FRAMES_RECORDED = new AtomicLong();
    public static final AtomicLong FRAMES_TRUNCATED_DEPTH = new AtomicLong();
    public static final AtomicLong FRAMES_TRUNCATED_TOTAL = new AtomicLong();
    public static final AtomicLong OBS_TRUNCATED = new AtomicLong();
    public static final AtomicLong ARMS_TRUNCATED = new AtomicLong();
    public static final AtomicLong OBS_REDACTED = new AtomicLong();
    public static final AtomicLong FRAMES_COLLAPSED = new AtomicLong();
    public static final AtomicLong FRAMES_FOLDED = new AtomicLong();

    public static final AtomicLong CONTEXTS_PROPAGATED = new AtomicLong();
    public static final AtomicLong COMMON_POOL_JOINS = new AtomicLong();
    public static final AtomicLong COMMON_POOL_WINDOW_REFUSED = new AtomicLong();

    public static final AtomicLong DOCS_SENT = new AtomicLong();
    public static final AtomicLong DOCS_DROPPED_QUEUE = new AtomicLong();
    public static final AtomicLong DOCS_FAILED = new AtomicLong();

    /** Latched by {@link TraceRuntime#failOpen}: once off, off for the life of the JVM. */
    public static final AtomicLong RUNTIME_FAILURES = new AtomicLong();

    /** Tier-1b conflict, verified after startup against the live ax-agent. */
    public static final AtomicLong STRIP_CONFLICT_UNRESOLVED = new AtomicLong();

    private static final Map<String, AtomicLong> SKIPS =
            new ConcurrentHashMap<String, AtomicLong>();

    private static final Map<String, Boolean> WARNED =
            new ConcurrentHashMap<String, Boolean>();

    public static void skip(String reason) { skip(reason, 1); }

    public static void skip(String reason, long n) {
        AtomicLong c = SKIPS.get(reason);
        if (c == null) {
            SKIPS.putIfAbsent(reason, new AtomicLong());
            c = SKIPS.get(reason);
        }
        c.addAndGet(n);
    }

    public static Map<String, AtomicLong> skips() {
        return Collections.unmodifiableMap(new TreeMap<String, AtomicLong>(SKIPS));
    }

    /** @return true the first time this key is seen. For "say it exactly once" warnings. */
    public static boolean warnOnce(String key) {
        return WARNED.putIfAbsent(key, Boolean.TRUE) == null;
    }

    public static void writeTo(TJson j) {
        j.objectStart("health");
        j.field("classesInstrumented", CLASSES_INSTRUMENTED.get());
        j.field("methodsInstrumented", METHODS_INSTRUMENTED.get());
        j.field("entriesInstrumented", ENTRIES_INSTRUMENTED.get());
        j.field("callSitesWrapped", CALL_SITES_WRAPPED.get());
        j.field("transformFailures", TRANSFORM_FAILURES.get());
        j.field("requestsSeen", REQUESTS_SEEN.get());
        j.field("tracesStarted", TRACES_STARTED.get());
        j.field("tracesCompleted", TRACES_COMPLETED.get());
        j.field("rejectedNoHeader", REJECTED_NO_HEADER.get());
        j.field("rejectedAuth", REJECTED_AUTH.get());
        j.field("rejectedRateCap", REJECTED_RATE_CAP.get());
        j.field("rejectedConcurrency", REJECTED_CONCURRENCY.get());
        j.field("framesRecorded", FRAMES_RECORDED.get());
        j.field("framesTruncatedDepth", FRAMES_TRUNCATED_DEPTH.get());
        j.field("framesTruncatedTotal", FRAMES_TRUNCATED_TOTAL.get());
        j.field("obsTruncated", OBS_TRUNCATED.get());
        j.field("armsTruncated", ARMS_TRUNCATED.get());
        j.field("obsRedacted", OBS_REDACTED.get());
        j.field("framesCollapsed", FRAMES_COLLAPSED.get());
        j.field("framesFolded", FRAMES_FOLDED.get());
        j.field("contextsPropagated", CONTEXTS_PROPAGATED.get());
        j.field("commonPoolJoins", COMMON_POOL_JOINS.get());
        j.field("commonPoolWindowRefused", COMMON_POOL_WINDOW_REFUSED.get());
        j.field("docsSent", DOCS_SENT.get());
        j.field("docsDroppedQueue", DOCS_DROPPED_QUEUE.get());
        j.field("docsFailed", DOCS_FAILED.get());
        j.field("runtimeFailures", RUNTIME_FAILURES.get());
        j.field("stripConflictUnresolved", STRIP_CONFLICT_UNRESOLVED.get());
        j.objectStart("skipped");
        for (Map.Entry<String, AtomicLong> e : skips().entrySet()) {
            j.field(e.getKey(), e.getValue().get());
        }
        j.objectEnd();
        j.objectEnd();
    }

    private TraceHealth() { throw new AssertionError(); }
}
