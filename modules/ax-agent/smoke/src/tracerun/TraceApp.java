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
            eq("master switch off: Tier-1b is NOT disabled by the trace tier",
                    false, tier1bDisabledByTrace());
            eq("master switch off: Tier-1b declined no class for a trace reason",
                    0L, tier1bTraceBlockedClasses());
        } else if ("conflict-refuse".equals(scenario)) {
            eq("Tier-1b overlap, policy=refuse: the trace tier did NOT arm", false, armed());
            eq("Tier-1b overlap, policy=refuse: nothing was instrumented for tracing",
                    0, framesRegistered());
            filter.doFilter(new FakeRequest("GET", "/search/101", "1"), resp, null);
            drain();
            eq("Tier-1b overlap, policy=refuse: 0 documents", 0L, documentsBuilt());
            eq("Tier-1b overlap, policy=refuse: ax.strip.enabled was NOT touched",
                    null, System.getProperty("ax.strip.enabled"));
            eq("Tier-1b overlap, policy=refuse: Tier-1b was NOT disabled",
                    false, tier1bDisabledByTrace());
        } else if ("conflict-disablestrip".equals(scenario)) {
            eq("Tier-1b overlap, policy=disable-strip (the default): the trace tier armed",
                    true, armed());
            // THE MERGE CHANGED THIS, AND THE NEW ASSERTION IS THE STRONGER ONE.
            // The old mechanism set ax.strip.enabled=false -- a JVM-WIDE switch -- from one
            // premain and hoped the other premain had not read it yet. One premain means the
            // decision is taken in-process and SCOPED: Tier-1b skips the intersection of the two
            // scopes and strips everything else. So the system property must still be untouched,
            // and the suppression must be visible as its own reported fact.
            eq("Tier-1b overlap, policy=disable-strip: ax.strip.enabled is NOT touched -- the "
                    + "suppression is scoped, not a JVM-wide switch",
                    null, System.getProperty("ax.strip.enabled"));
            eq("Tier-1b overlap, policy=disable-strip: tier1bDisabledByTrace is reported",
                    true, tier1bDisabledByTrace());
            filter.doFilter(new FakeRequest("GET", "/search/101", "1"), resp, null);
            drain();
            eq("Tier-1b overlap, policy=disable-strip: the traced request produced 1 document",
                    1L, documentsBuilt());
        } else if ("stripscope".equals(scenario)) {
            // THE ASSERTION THAT MAKES "SCOPED" A PROPERTY AND NOT A CLAIM.
            //
            // Both classes are inside ax.include.packages, so both carry tier-1 probes and both
            // are Tier-1b candidates. Only traceapp.* is inside ax.trace.include.packages.
            // Tier-1b must therefore refuse ONE of them and strip the OTHER, and the refusal
            // must be reported rather than inferred.
            eq("scoped strip: the trace tier armed", true, armed());
            eq("scoped strip: Tier-1b is reported as disabled by the trace tier",
                    true, tier1bDisabledByTrace());

            stripcheck.Plain plain = new stripcheck.Plain();
            plain.work(7);
            plain.describe(2);
            filter.doFilter(new FakeRequest("GET", "/search/101", "1"), resp, null);
            drain();

            eq("scoped strip: a class OUTSIDE the traced scope still de-instruments",
                    true, stripNow("stripcheck.Plain"));
            eq("scoped strip: a class INSIDE the traced scope does NOT de-instrument",
                    false, stripNow("traceapp.SearchService"));
            eq("scoped strip: the out-of-scope class is reported as stripped",
                    true, stripped("stripcheck.Plain"));
            eq("scoped strip: the traced class is NOT reported as stripped",
                    false, stripped("traceapp.SearchService"));
            gtL("scoped strip: the refusal is counted, not silent", 0L,
                    tier1bTraceBlockedClasses());
            // And it has to reach the WIRE, or the server and the UI cannot render WHY
            // steady-state overhead is not zero on this JVM. flushNow() builds AND sends one
            // window synchronously, so this needs no sleep and no timing assumption.
            flushNow();
            gt("scoped strip: one agentHealth window reached /v1/ingest", 0, INGEST_POSTS.get());
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

    // ---------------- agent hooks, reflectively ----------------
    //
    // SCOPE-v3.1: these used to live on io.auxin.trace.TraceAgent, which was a second
    // -javaagent's premain class. There is one agent now, so they live on AuxinAgent under
    // `trace`-prefixed names -- `active()` and `armed()` already mean something there.
    //
    // Reflection, not a direct call, for one reason: the "inert" scenario runs with
    // ax.trace.enabled=false, and the "conflict-refuse" scenario runs with the tier refusing
    // to arm. In both the agent jar IS on the classpath, so a direct call would work -- but
    // reflection also lets this harness run with NO agent at all, which is how the baseline for
    // "byte-for-byte what it would be without this agent" is taken.

    private static final String AGENT = "io.auxin.agent.AuxinAgent";

    private static Object call(String name) {
        try {
            Class<?> c = Class.forName(AGENT);
            Method m = c.getMethod(name);
            return m.invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            System.out.println("  (hook " + name + " failed: " + t + ")");
            return null;
        }
    }

    private static boolean active() {
        Object o = call("traceActive");
        return Boolean.TRUE.equals(o);
    }

    private static boolean armed() { Object o = call("traceArmed"); return Boolean.TRUE.equals(o); }

    private static int framesRegistered() { return intOf(call("traceFramesRegistered")); }

    private static int armsRegistered() { return intOf(call("traceArmsRegistered")); }

    private static int observationsRegistered() {
        return intOf(call("traceObservationsRegistered"));
    }

    private static int activeTraces() { return intOf(call("traceActiveTraces")); }

    /** SCOPE-v3.1: is Tier-1b's auto-strip suppressed for the traced scope on this JVM? */
    private static boolean tier1bDisabledByTrace() {
        return Boolean.TRUE.equals(call("tier1bDisabledByTrace"));
    }

    /** SCOPE-v3.1: has Tier-1b actually declined a class for that reason yet? */
    private static long tier1bTraceBlockedClasses() {
        Object o = call("tier1bTraceBlockedClasses");
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** Forces one coverage window out of band: builds it, serialises it and POSTs it. */
    private static void flushNow() {
        try {
            Class<?> c = Class.forName(AGENT);
            c.getMethod("flushNow").invoke(null);
        } catch (Throwable t) {
            System.out.println("  (flushNow failed: " + t + ")");
        }
    }

    /** Tier-1b on demand, by dotted class name. Returns false when the class is not loadable. */
    private static boolean stripNow(String dotted) {
        try {
            Class<?> c = Class.forName(AGENT);
            Class<?> subject = Class.forName(dotted);
            Object r = c.getMethod("stripNow", Class.class).invoke(null, subject);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            System.out.println("  (stripNow(" + dotted + ") failed: " + t + ")");
            return false;
        }
    }

    /** Does Tier-1b believe this class is de-instrumented right now? */
    private static boolean stripped(String dotted) {
        try {
            Class<?> c = Class.forName(AGENT);
            Object r = c.getMethod("stripped", String.class).invoke(null, dotted);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            System.out.println("  (stripped(" + dotted + ") failed: " + t + ")");
            return false;
        }
    }

    private static long documentsBuilt() {
        Object o = call("traceDocumentsBuilt");
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static int intOf(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    private static void forceFailOpen(String why) {
        try {
            Class<?> c = Class.forName(AGENT);
            c.getMethod("traceForceFailOpen", String.class).invoke(null, why);
        } catch (Throwable t) {
            System.out.println("  (forceFailOpen failed: " + t + ")");
        }
    }

    private static void drain() {
        try {
            Class<?> c = Class.forName(AGENT);
            c.getMethod("traceDrain", long.class).invoke(null, Long.valueOf(4000L));
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

    private static void gtL(String what, long floor, long actual) {
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
