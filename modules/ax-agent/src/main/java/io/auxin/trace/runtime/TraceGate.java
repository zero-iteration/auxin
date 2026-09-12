package io.auxin.trace.runtime;

import io.auxin.trace.util.TLog;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ACTIVATION. The servlet entry, and the only place a trace can start.
 *
 * <h3>A header, not a query parameter</h3>
 * {@code X-Auxin-Trace: 1}. The reporter's own service logs query parameters verbatim into
 * Elasticsearch as {@code clientQueryParams}, so a {@code ?trace=1} activation would write the
 * activation into the log index of the very system being debugged — and query parameters also
 * pollute HTTP cache keys, CDN keys and Spring's own {@code @Cacheable} keys, which means a
 * traced request is not the same request. A header is invisible to both.
 *
 * <h3>Where this is installed</h3>
 * By {@code TraceEmitter} on any method matching a servlet entry signature:
 * {@code Filter#doFilter}, {@code HttpServlet#service} and Spring's
 * {@code DispatcherServlet#doService}, in both the {@code javax.servlet} and
 * {@code jakarta.servlet} namespaces. Matching is by <b>(name, descriptor)</b> and not by class
 * hierarchy, which is what makes it work on any implementing class without the transformer
 * having to load or walk the servlet API.
 *
 * <h3>The gate, again</h3>
 * {@link #begin} is {@code if (!ARMED) return;} first. When the master switch is off the entry
 * is not instrumented at all, so this is the second line: it also covers the case where the
 * tracer latched itself off mid-flight.
 *
 * <h3>Reflection, and why it is acceptable exactly here</h3>
 * The header is read with a cached {@code getHeader(String)} {@link Method} rather than a
 * compile-time dependency on the servlet API, because this module may add no dependency and
 * must work against both {@code javax} and {@code jakarta}. That is one reflective invoke per
 * request that carries the header, on the entry method only — never per call, never inside a
 * frame probe. A request that does not carry the header pays the reflective {@code getHeader}
 * once; that is measured and reported, and it is the one place this module is not ~1ns.
 */
public final class TraceGate {

    /** Master switch. Mirrors {@code ax.trace.enabled}; false disarms activation entirely. */
    private static volatile boolean ARMED;

    private static volatile String headerName = "X-Auxin-Trace";
    private static volatile String token = "";
    private static volatile boolean recordPath = true;
    private static volatile RateCap rateCap = new RateCap(0);
    private static volatile TraceSink sink;

    private static volatile int maxFrames = 20000;
    private static volatile int maxDepth = 256;
    private static volatile int maxObsPerFrame = 64;
    private static volatile int maxArmsPerFrame = 64;

    /** Where a finished trace goes. Implemented by the dispatcher; never called on the app thread's critical path beyond an offer. */
    public interface TraceSink {
        void submit(TraceContext ctx);
    }

    /** Reflective {@code getHeader}/{@code getMethod}/{@code getRequestURI}, per request class. */
    private static final Map<Class<?>, Method[]> ACCESSORS =
            new ConcurrentHashMap<Class<?>, Method[]>();
    private static final Method[] NONE = new Method[0];

    private static final AtomicLong SEQ = new AtomicLong();
    private static final String INSTANCE =
            Long.toHexString(System.nanoTime() ^ (((long) new Object().hashCode()) << 21));

    /** Per-thread nesting depth of instrumented entry methods (Filter -> Servlet -> ...). */
    private static final ThreadLocal<int[]> ENTRY_DEPTH = new ThreadLocal<int[]>();
    /** The context this thread started, if any. */
    private static final ThreadLocal<TraceContext> OWN = new ThreadLocal<TraceContext>();

    public static void install(String header, String tok, RateCap cap, TraceSink s,
                               boolean path, int frames, int depth, int obsPerFrame,
                               int armsPerFrame, boolean on) {
        headerName = header;
        token = tok == null ? "" : tok;
        rateCap = cap;
        sink = s;
        recordPath = path;
        maxFrames = frames;
        maxDepth = depth;
        maxObsPerFrame = obsPerFrame;
        maxArmsPerFrame = armsPerFrame;
        ARMED = on;                          // MUST be last: it publishes everything above
    }

    public static void disarm() { ARMED = false; }

    public static boolean armed() { return ARMED; }

    public static String headerName() { return headerName; }

    public static RateCap rateCap() { return rateCap; }

    // ---------------- probe entry points ----------------

    /**
     * Request entry. {@code request} is the {@code ServletRequest} — untyped, because this module
     * cannot name the servlet API.
     */
    public static void begin(Object request) {
        if (!ARMED) return;
        onBegin(request);
    }

    /**
     * Request exit, from every return AND from the {@code catch (Throwable)} handler.
     *
     * <p>Gated on {@code ARMED || activeTraces != 0} rather than on {@code ARMED} alone: a trace
     * that started before someone flipped the master switch off must still be closed, or
     * {@code ACTIVE} stays non-zero for ever and every probe in the JVM pays a thread-local read.
     */
    public static void end() {
        if (!ARMED && TraceRuntime.activeTraces() == 0) return;
        onEnd();
    }

    private static void onBegin(Object request) {
        try {
            int[] d = ENTRY_DEPTH.get();
            if (d == null) {
                d = new int[1];
                ENTRY_DEPTH.set(d);
            }
            if (d[0]++ > 0) return;             // a nested entry (Filter -> Servlet): one trace

            TraceHealth.REQUESTS_SEEN.incrementAndGet();

            Method[] acc = accessors(request);
            if (acc == NONE) {
                if (TraceHealth.warnOnce("entryNoGetHeader:" + request.getClass().getName())) {
                    TLog.warn("the entry method's request argument ("
                            + request.getClass().getName() + ") has no public "
                            + "getHeader(String): no request can ever be traced through it. "
                            + "Counted as entryNoGetHeader.");
                }
                TraceHealth.skip("entryNoGetHeader");
                return;
            }
            String value = (String) acc[0].invoke(request, headerName);
            if (value == null || value.length() == 0) {
                TraceHealth.REJECTED_NO_HEADER.incrementAndGet();
                return;
            }
            // CONSTANT-TIME-ISH comparison is not the point here (the token is not a password
            // hash and the attacker already has to be able to set a header); refusing a wrong
            // token loudly enough to alert on IS the point.
            String tok = token;
            if (tok.isEmpty()) {
                if (!"1".equals(value) && !"true".equalsIgnoreCase(value)) {
                    TraceHealth.REJECTED_AUTH.incrementAndGet();
                    return;
                }
            } else if (!tok.equals(value)) {
                TraceHealth.REJECTED_AUTH.incrementAndGet();
                return;
            }
            if (!rateCap.tryAcquire()) {
                TraceHealth.REJECTED_RATE_CAP.incrementAndGet();
                return;
            }

            TraceContext ctx = new TraceContext(nextTraceId(), maxFrames, maxDepth,
                    maxObsPerFrame, maxArmsPerFrame);
            if (TraceRuntime.beginTrace(ctx) == null) return;   // concurrency cap, already counted
            OWN.set(ctx);
            if (recordPath) {
                ctx.requestMethod = safeString(acc, 1, request);
                ctx.requestPath = mask(safeString(acc, 2, request));
            }
        } catch (Throwable t) {
            // Includes anything a hostile or unusual request implementation throws out of
            // getHeader. Never reaches the application.
            TraceHealth.RUNTIME_FAILURES.incrementAndGet();
            if (TraceHealth.warnOnce("beginFailed")) {
                TLog.warn("trace activation failed and was skipped for this request: " + t, t);
            }
        }
    }

    private static void onEnd() {
        try {
            int[] d = ENTRY_DEPTH.get();
            if (d == null) return;
            if (--d[0] > 0) return;
            d[0] = 0;
            TraceContext ctx = OWN.get();
            if (ctx == null) return;
            OWN.remove();
            TraceRuntime.endTrace(ctx);
            TraceSink s = sink;
            if (s != null) s.submit(ctx);
        } catch (Throwable t) {
            TraceHealth.RUNTIME_FAILURES.incrementAndGet();
            if (TraceHealth.warnOnce("endFailed")) {
                TLog.warn("trace completion failed: " + t, t);
            }
        }
    }

    private static String safeString(Method[] acc, int i, Object request) {
        try {
            if (i >= acc.length || acc[i] == null) return null;
            Object v = acc[i].invoke(request);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method[] accessors(Object request) {
        Class<?> c = request.getClass();
        Method[] cached = ACCESSORS.get(c);
        if (cached != null) return cached;
        Method[] out;
        try {
            Method getHeader = c.getMethod("getHeader", String.class);
            Method getMethod = optional(c, "getMethod");
            Method getUri = optional(c, "getRequestURI");
            if (getUri == null) getUri = optional(c, "getServletPath");
            out = new Method[]{getHeader, getMethod, getUri};
        } catch (Throwable t) {
            out = NONE;
        }
        ACCESSORS.put(c, out);
        return out;
    }

    private static Method optional(Class<?> c, String name) {
        try {
            return c.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The request path, with every identifier-shaped segment masked.
     *
     * <p>This is the one place the tracer records something derived from request content, and it
     * is a deliberate, narrowed exception: without a path a trace document is very hard to match
     * to the request a human is debugging. The QUERY STRING is never read at all (it is not even
     * fetched), digits collapse to {@code #}, and UUID- and hash-shaped segments become
     * {@code {id}}. {@code ax.trace.record.path=false} removes even this.
     */
    static String mask(String uri) {
        if (uri == null) return null;
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        StringBuilder sb = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            int slash = path.indexOf('/', i);
            int end = slash < 0 ? path.length() : slash;
            String seg = path.substring(i, end);
            sb.append(maskSegment(seg));
            if (slash < 0) break;
            sb.append('/');
            i = end + 1;
        }
        return sb.toString();
    }

    private static String maskSegment(String seg) {
        if (seg.length() == 0) return seg;
        int digits = 0;
        int hex = 0;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c >= '0' && c <= '9') { digits++; hex++; }
            else if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '-') hex++;
        }
        if (digits == seg.length()) return "#";
        if (hex == seg.length() && seg.length() >= 16) return "{id}";
        if (digits > 0) {
            StringBuilder sb = new StringBuilder(seg.length());
            boolean run = false;
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                if (c >= '0' && c <= '9') {
                    if (!run) { sb.append('#'); run = true; }
                } else {
                    sb.append(c);
                    run = false;
                }
            }
            return sb.toString();
        }
        return seg;
    }

    private static String nextTraceId() {
        return INSTANCE + "-" + Long.toHexString(SEQ.incrementAndGet());
    }

    private TraceGate() { throw new AssertionError(); }
}
