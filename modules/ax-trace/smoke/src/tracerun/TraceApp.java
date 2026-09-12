package tracerun;

import traceapp.FakeRequest;
import traceapp.SearchFilter;
import traceapp.SearchService;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * The smoke harness. Deliberately in a package that is NOT in
 * {@code ax.trace.include.packages}, so the harness's own frames can never appear in a trace
 * document and every frame in a document belongs to {@code traceapp}.
 *
 * <p>It stands up a real collector on {@code POST /v1/trace} and a second handler on
 * {@code /v1/ingest} that records anything that lands there — so "the trace document goes to a
 * SEPARATE endpoint and cannot interfere with the aggregate ingest path" is an assertion and not
 * a claim.
 */
public final class TraceApp {

    private static int checks;
    private static int fails;

    private static final AtomicInteger TRACE_POSTS = new AtomicInteger();
    private static final AtomicInteger INGEST_POSTS = new AtomicInteger();
    private static volatile String recvDir;

    public static void main(String[] args) throws Exception {
        final String scenario = System.getProperty("trace.smoke.scenario", "full");
        recvDir = System.getProperty("trace.smoke.recv", "");
        final int port = Integer.parseInt(System.getProperty("trace.smoke.port", "0"));

        HttpServer server = startCollector(port);
        try {
            System.out.println("scenario=" + scenario + " collectorPort="
                    + server.getAddress().getPort());
            run(scenario);
        } finally {
            server.stop(0);
        }

        System.out.println();
        System.out.println("APP CHECKS " + (checks - fails) + "/" + checks);
        if (fails > 0) {
            System.out.println("APP RESULT FAIL");
            System.exit(1);
        }
        System.out.println("APP RESULT PASS");
    }

    private static void run(String scenario) throws Exception {
        SearchService service = new SearchService(new SearchService.Flags(true));
        final SearchService svc = service;
        FilterChain chain = new FilterChain() {
            @Override public void doFilter(ServletRequest req, ServletResponse resp) {
                svc.handle(req.getRequestURI(), -1);
            }
        };
        SearchFilter filter = new SearchFilter(chain);
        Response resp = new Response();

        if ("inert".equals(scenario)) {
            eq("master switch off: TraceAgent is not active", false, active());
            eq("master switch off: activation is not armed", false, armed());
            eq("master switch off: nothing was instrumented (0 frame sites registered)",
                    0, framesRegistered());
            for (int i = 0; i < 3; i++) {
                filter.doFilter(new FakeRequest("GET", "/search/101", "1"), resp, null);
            }
            drain();
            eq("master switch off: 3 requests WITH the header produced 0 documents",
                    0L, documentsBuilt());
            eq("master switch off: nothing was POSTed to /v1/trace", 0, TRACE_POSTS.get());
        } else if ("conflict-refuse".equals(scenario)) {
            eq("Tier-1b conflict, policy=refuse: the tracer did NOT arm", false, armed());
            eq("Tier-1b conflict, policy=refuse: nothing was instrumented",
                    0, framesRegistered());
            filter.doFilter(new FakeRequest("GET", "/search/101", "1"), resp, null);
            drain();
            eq("Tier-1b conflict, policy=refuse: 0 documents", 0L, documentsBuilt());
            eq("Tier-1b conflict, policy=refuse: ax.strip.enabled was NOT touched",
                    null, System.getProperty("ax.strip.enabled"));
        } else if ("conflict-disablestrip".equals(scenario)) {
            eq("Tier-1b conflict, policy=disable-strip: the tracer armed", true, armed());
            eq("Tier-1b conflict, policy=disable-strip: ax.strip.enabled was set to false",
                    "false", System.getProperty("ax.strip.enabled"));
            filter.doFilter(new FakeRequest("GET", "/search/101", "1"), resp, null);
            drain();
            eq("Tier-1b conflict, policy=disable-strip: the traced request produced 1 document",
                    1L, documentsBuilt());
        } else if ("prodnotoken".equals(scenario)) {
            eq("production + no token: the tracer did NOT arm", false, armed());
            eq("production + no token: nothing was instrumented", 0, framesRegistered());
        } else if ("token".equals(scenario)) {
            eq("token set: the tracer armed", true, armed());
            filter.doFilter(new FakeRequest("GET", "/search/1", "1"), resp, null);
            filter.doFilter(new FakeRequest("GET", "/search/2", "wrong"), resp, null);
            drain();
            eq("token set: a header of '1' and a header of 'wrong' both produced 0 documents",
                    0L, documentsBuilt());
            filter.doFilter(new FakeRequest("GET", "/search/3", "s3cret"), resp, null);
            drain();
            eq("token set: the correct token produced 1 document", 1L, documentsBuilt());
        } else if ("ratecap".equals(scenario)) {
            eq("rate cap: the tracer armed", true, armed());
            for (int i = 0; i < 6; i++) {
                filter.doFilter(new FakeRequest("GET", "/search/" + i, "1"), resp, null);
            }
            drain();
            eq("rate cap of 2: 6 traced requests produced exactly 2 documents",
                    2L, documentsBuilt());
            eq("rate cap of 2: 6 traced requests POSTed exactly 2 documents", 2, TRACE_POSTS.get());
            eq("rate cap: the gate returned to zero", 0, activeTraces());
        } else if ("failopen".equals(scenario)) {
            filter.doFilter(new FakeRequest("GET", "/search/1", "1"), resp, null);
            drain();
            eq("fail-open: the first traced request produced 1 document", 1L, documentsBuilt());
            forceFailOpen("smoke");
            filter.doFilter(new FakeRequest("GET", "/search/2", "1"), resp, null);
            drain();
            eq("fail-open: after the latch, a traced request produces no further document",
                    1L, documentsBuilt());
            eq("fail-open: the gate is zero after the latch", 0, activeTraces());
        } else if ("noparallel".equals(scenario)) {
            filter.doFilter(new FakeRequest("GET", "/search/101", "1"), resp, null);
            drain();
            eq("parallelstream.window=false: the traced request still produced 1 document",
                    1L, documentsBuilt());
        } else {
            // ---- the main scenario ----
            eq("armed", true, armed());
            gt("instrumentation installed frame sites", 10, framesRegistered());
            gt("instrumentation installed observation sites", 10, observationsRegistered());
            gt("instrumentation installed branch-arm sites", 0, armsRegistered());

            // 1. AN UNTRACED REQUEST RECORDS NOTHING.
            for (int i = 0; i < 5; i++) {
                filter.doFilter(new FakeRequest("GET", "/search/101", null), resp, null);
            }
            drain();
            eq("5 requests with NO header: 0 documents", 0L, documentsBuilt());
            eq("5 requests with NO header: 0 POSTs to /v1/trace", 0, TRACE_POSTS.get());
            eq("5 requests with NO header: the gate never left zero", 0, activeTraces());

            // 2. A TRACED REQUEST.
            filter.doFilter(new FakeRequest("POST", "/search/101?trace=1&pax=jane", "1"),
                    resp, null);
            drain();
            eq("one X-Auxin-Trace: 1 request: 1 document", 1L, documentsBuilt());
            eq("one X-Auxin-Trace: 1 request: 1 POST to /v1/trace", 1, TRACE_POSTS.get());
            eq("NOTHING was POSTed to /v1/ingest (separate endpoint)", 0, INGEST_POSTS.get());
            eq("the gate returned to zero after the request", 0, activeTraces());

            // 3. Untraced requests AFTER a trace still record nothing.
            long before = documentsBuilt();
            for (int i = 0; i < 5; i++) {
                filter.doFilter(new FakeRequest("GET", "/search/101", null), resp, null);
            }
            drain();
            eq("5 more requests with no header after a trace: still no new document",
                    before, documentsBuilt());
        }
        service.shutdown();
    }

    // ---------------- the collector ----------------

    private static HttpServer startCollector(int port) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.createContext("/v1/trace", new Recorder(TRACE_POSTS, "trace"));
        // Present on purpose: if a trace document ever landed on the aggregate route, this would
        // count it. The assertion that it stays at zero is the proof of separation.
        s.createContext("/v1/ingest", new Recorder(INGEST_POSTS, "ingest"));
        s.setExecutor(null);
        s.start();
        System.setProperty("trace.smoke.actualPort", String.valueOf(s.getAddress().getPort()));
        return s;
    }

    private static final class Recorder implements HttpHandler {
        private final AtomicInteger counter;
        private final String tag;

        Recorder(AtomicInteger counter, String tag) { this.counter = counter; this.tag = tag; }

        @Override public void handle(HttpExchange x) throws java.io.IOException {
            byte[] raw = readAll(x.getRequestBody());
            byte[] body = raw;
            if ("gzip".equals(x.getRequestHeaders().getFirst("Content-Encoding"))) {
                body = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(raw)));
            }
            int n = counter.incrementAndGet();
            String dir = recvDir;
            if (dir != null && !dir.isEmpty()) {
                File d = new File(dir);
                d.mkdirs();
                FileOutputStream out = new FileOutputStream(new File(d, tag + "-" + n + ".json"));
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            }
            x.sendResponseHeaders(202, 0);
            OutputStream os = x.getResponseBody();
            os.close();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static final class Response implements HttpServletResponse {
        int status;

        @Override public void setStatus(int s) { this.status = s; }
    }

    // ---------------- TraceAgent hooks, reflectively ----------------
    //
    // Reflection, not a direct call, for one reason: the "inert" scenario runs with
    // ax.trace.enabled=false, and the "conflict-refuse" scenario runs with the tracer refusing
    // to arm. In both the agent jar IS on the classpath, so a direct call would work -- but
    // reflection also lets this harness run with NO agent at all, which is how the baseline for
    // "byte-for-byte what it would be without this agent" is taken.

    private static Object call(String name) {
        try {
            Class<?> c = Class.forName("io.auxin.trace.TraceAgent");
            Method m = c.getMethod(name);
            return m.invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            System.out.println("  (hook " + name + " failed: " + t + ")");
            return null;
        }
    }

    private static boolean active() { Object o = call("active"); return Boolean.TRUE.equals(o); }

    private static boolean armed() { Object o = call("armed"); return Boolean.TRUE.equals(o); }

    private static int framesRegistered() { return intOf(call("framesRegistered")); }

    private static int armsRegistered() { return intOf(call("armsRegistered")); }

    private static int observationsRegistered() { return intOf(call("observationsRegistered")); }

    private static int activeTraces() { return intOf(call("activeTraces")); }

    private static long documentsBuilt() {
        Object o = call("documentsBuilt");
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static int intOf(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    private static void forceFailOpen(String why) {
        try {
            Class<?> c = Class.forName("io.auxin.trace.TraceAgent");
            c.getMethod("forceFailOpen", String.class).invoke(null, why);
        } catch (Throwable t) {
            System.out.println("  (forceFailOpen failed: " + t + ")");
        }
    }

    private static void drain() {
        try {
            Class<?> c = Class.forName("io.auxin.trace.TraceAgent");
            c.getMethod("drain", long.class).invoke(null, Long.valueOf(4000L));
        } catch (ClassNotFoundException e) {
            return;
        } catch (Throwable t) {
            System.out.println("  (drain failed: " + t + ")");
        }
        // The dispatcher builds and POSTs after the queue is drained, so give the POST a moment.
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------- assertions ----------------

    private static void eq(String what, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS  " + what);
        } else {
            System.out.println("  FAIL  " + what + " (expected " + expected + ", got " + actual + ")");
            fails++;
        }
    }

    private static void eq(String what, boolean expected, boolean actual) {
        eq(what, Boolean.valueOf(expected), Boolean.valueOf(actual));
    }

    private static void eq(String what, int expected, int actual) {
        eq(what, Integer.valueOf(expected), Integer.valueOf(actual));
    }

    private static void eq(String what, long expected, long actual) {
        eq(what, Long.valueOf(expected), Long.valueOf(actual));
    }

    private static void gt(String what, int floor, int actual) {
        checks++;
        if (actual > floor) {
            System.out.println("  PASS  " + what + " (" + actual + " > " + floor + ")");
        } else {
            System.out.println("  FAIL  " + what + " (expected > " + floor + ", got " + actual + ")");
            fails++;
        }
    }

    private TraceApp() { }
}
