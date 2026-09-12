package g5;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GATE G5 — in-JVM half of the three-agent coexistence harness.
 *
 * <p>Everything below runs inside the ONE JVM that has ax-agent, the JaCoCo agent and the
 * OpenTelemetry javaagent attached at the same time. The sequence is load bearing:
 *
 * <ol>
 *   <li><b>traffic</b> — {@code io.auxin.demo.App.main} starts the HTTP server and drives
 *       its own deterministic load. The OTel/SkyWalking conflict this gate exists to catch was
 *       invisible until the first request, so nothing here may be a startup-only check.</li>
 *   <li><b>ax-agent window</b> — flushed to {@code ax-window.json}.</li>
 *   <li><b>JaCoCo mid-run dump</b> — {@code org.jacoco.agent.rt.RT.getAgent().getExecutionData()}
 *       to {@code jacoco-mid.exec}. This must happen BEFORE step 4, because step 4 deliberately
 *       calls methods the fixture declares dead and JaCoCo would record them.</li>
 *   <li><b>Tier-1b</b> — strip a class, prove the strip, then let a genuinely third-party agent
 *       retransform it and record what happens to our probes.</li>
 *   <li><b>post-experiment traffic</b> — a fresh {@code com.sun.net.httpserver} server on
 *       {@code /g5postcheck} hit over {@code HttpURLConnection}, so the log can be asked whether
 *       OTel is <i>still</i> producing spans after a strip and a foreign retransform.</li>
 * </ol>
 *
 * <p>All access to the demo application, ax-agent and JaCoCo goes through reflection: this class
 * is compiled {@code --release 8} and has to sit next to demo classes at class-file 55 or 61 on
 * a JDK 11, 17 or 21 runtime.
 */
public final class Harness {

    private static final String AGENT = "io.auxin.agent.AuxinAgent";
    private static final String HEALTH = "io.auxin.agent.health.Health";
    private static final String OBSERVER = "g5obs.OrderObserver";
    private static final String JACOCO_RT = "org.jacoco.agent.rt.RT";

    private static final String CUSTOMER = "io.auxin.demo.model.Customer";
    private static final String CUSTOMER_NAME_DESC = "()Ljava/lang/String;";
    private static final String CUSTOMER_ANON_DESC = "()Lio/auxin/demo/model/Customer;";

    /** Distinctive path so the OTel span log can be asked "did you see traffic after all that?". */
    private static final String POST_CHECK_PATH = "/g5postcheck";

    public static void main(String[] args) throws Exception {
        String outDir = arg(args, "--out", ".");
        List<String> demoArgs = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--demo:")) demoArgs.add(args[i].substring(7));
        }

        System.out.println("[g5] java " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("[g5] gtAgent=" + gtActive()
                + " jacocoRT=" + present(JACOCO_RT)
                + " observer=" + present(OBSERVER));

        phase("load-and-traffic");

        // ---- 1. the application, under real traffic ----
        Class<?> app = Class.forName("io.auxin.demo.App");
        app.getMethod("main", String[].class).invoke(null, (Object) demoArgs.toArray(new String[0]));

        // ---- 2. ax-agent coverage window, BEFORE anything else is touched ----
        if (gtActive()) {
            String json = (String) m(AGENT, "flushNow").invoke(null);
            write(outDir + "/ax-window.json", json);
            System.out.println("[g5] wrote ax-window.json (" + json.length() + " bytes)");
        } else {
            System.out.println("[g5] ax-agent NOT active: no coverage window");
        }

        // ---- 3. JaCoCo mid-run dump, BEFORE the Tier-1b experiment pollutes it ----
        dumpJacoco(outDir + "/jacoco-mid.exec");

        // ---- 4. Tier-1b + third-party retransform ----
        Map<String, Object> t1b = new LinkedHashMap<String, Object>();
        try {
            tier1b(t1b);
        } catch (Throwable t) {
            t1b.put("error", String.valueOf(t));
            t.printStackTrace();
        }

        // ---- 5. is OTel still alive after all of that? ----
        try {
            phase("post-check-traffic");
            int n = postCheckTraffic();
            t1b.put("D1.postCheckRequests", Integer.valueOf(n));
        } catch (Throwable t) {
            t1b.put("D1.postCheckError", String.valueOf(t));
        }
        write(outDir + "/tier1b.json", json(t1b));

        // ---- 6. a second window, so post-strip probe state is visible on the wire ----
        if (gtActive()) {
            write(outDir + "/ax-window-2.json", (String) m(AGENT, "flushNow").invoke(null));
        }

        // ---- 7. the ordering observer's transform log ----
        if (present(OBSERVER)) {
            m(OBSERVER, "write", String.class).invoke(null, outDir + "/xform-events.tsv");
            System.out.println("[g5] wrote xform-events.tsv");
        }

        System.out.println("[g5] harness done");
        Thread.sleep(1500L);          // let the OTel BatchSpanProcessor pick up the tail
        System.exit(0);               // runs shutdown hooks: JaCoCo dumponexit + OTel flush
    }

    // =====================================================================================
    // Tier-1b: strip, prove the strip, then let a THIRD PARTY retransform and see what happens
    // =====================================================================================
    private static void tier1b(Map<String, Object> r) throws Exception {
        r.put("gtActive", Boolean.valueOf(gtActive()));
        boolean obs = present(OBSERVER)
                && ((Boolean) m(OBSERVER, "available").invoke(null)).booleanValue();
        r.put("observerAvailable", Boolean.valueOf(obs));
        if (!gtActive()) return;

        Method probes = m(AGENT, "probes", String.class);
        Method probeIndex = m(AGENT, "probeIndex", String.class, String.class, String.class);
        Method stripNow = m(AGENT, "stripNow", Class.class);

        // ------------------------------------------------------------------
        // Part A — behavioural proof, on a class with two never-invoked methods
        // ------------------------------------------------------------------
        Class<?> customer = Class.forName(CUSTOMER);
        int idxName = ((Integer) probeIndex.invoke(null, CUSTOMER, "name", CUSTOMER_NAME_DESC)).intValue();
        int idxAnon = ((Integer) probeIndex.invoke(null, CUSTOMER, "anonymize", CUSTOMER_ANON_DESC)).intValue();
        r.put("customerProbeIdx.name", Integer.valueOf(idxName));
        r.put("customerProbeIdx.anonymize", Integer.valueOf(idxAnon));

        boolean[] p = (boolean[]) probes.invoke(null, CUSTOMER);
        r.put("customerInstrumented", Boolean.valueOf(p != null));
        if (p == null || idxName < 0 || idxAnon < 0) {
            r.put("skipped", "Customer was not instrumented (see classesSkipped in the window)");
            return;
        }
        r.put("A0.name.before", Boolean.valueOf(p[idxName]));
        r.put("A0.anonymize.before", Boolean.valueOf(p[idxAnon]));
        // -parameters is on for the demo build. Any retransform replays the JVM's CACHED class
        // bytes, and on some JDKs those do not carry MethodParameters -- so a live class can
        // lose its parameter names the moment any agent retransforms it. Measure, do not infer.
        r.put("A0.parameterNamesPresent", Boolean.valueOf(parameterNamesPresent(customer)));

        long strippedBefore = health("classesStripped");
        r.put("A0.classesStrippedDuringTraffic", Long.valueOf(strippedBefore));
        phase("gt-strip");
        boolean stripOk = ((Boolean) stripNow.invoke(null, customer)).booleanValue();
        r.put("A1.stripNowReturned", Boolean.valueOf(stripOk));
        r.put("A1.classesStrippedDelta", Long.valueOf(health("classesStripped") - strippedBefore));
        r.put("A1.stripFailures", Long.valueOf(health("stripFailures")));

        // The class must still work, and the probe must no longer fire.
        phase("post-strip-call");
        Constructor<?> ctor = customer.getConstructor(String.class, String.class, String.class);
        Object c1 = ctor.newInstance("c-strip", "Ada", "vip");
        Object nameValue = customer.getMethod("name").invoke(c1);
        p = (boolean[]) probes.invoke(null, CUSTOMER);
        r.put("A2.name.callable", Boolean.valueOf("Ada".equals(nameValue)));
        r.put("A2.name.probeAfterCall", Boolean.valueOf(p[idxName]));
        r.put("A2.stripEffective", Boolean.valueOf(!p[idxName]));
        r.put("A3.parameterNamesPresentAfterStrip",
                Boolean.valueOf(parameterNamesPresent(customer)));

        // ------------------------------------------------------------------
        // Part B — a THIRD PARTY retransforms the class we just stripped
        // ------------------------------------------------------------------
        boolean retransformed = false;
        if (obs) {
            phase("thirdparty-retransform");
            retransformed = ((Boolean) m(OBSERVER, "retransform", Class.class)
                    .invoke(null, customer)).booleanValue();
        }
        r.put("B1.thirdPartyRetransformed", Boolean.valueOf(retransformed));

        if (retransformed) {
            phase("post-thirdparty-call");
            Object c2 = ctor.newInstance("c-reappear", "Grace", "std");
            Object anon = customer.getMethod("anonymize").invoke(c2);
            p = (boolean[]) probes.invoke(null, CUSTOMER);
            r.put("B2.anonymize.callable", Boolean.valueOf(anon != null));
            // TRUE means the probe came BACK: the JVM replayed ProbeInstaller's cached, probed
            // bytes as the input to the third party's retransform, and Tier-1b was undone.
            r.put("B3.probesReappeared", Boolean.valueOf(p[idxAnon]));

            long strippedNow = health("classesStripped");
            Thread.sleep(2500L);      // several drain cycles
            r.put("B4.classesStrippedAfterWait", Long.valueOf(health("classesStripped")));
            r.put("B4.reStripped", Boolean.valueOf(health("classesStripped") > strippedNow));

            r.put("B6.parameterNamesPresentAfterForeignRetransform",
                    Boolean.valueOf(parameterNamesPresent(customer)));

            Object c3 = ctor.newInstance("c-check", "Hopper", "std");
            customer.getMethod("name").invoke(c3);
            p = (boolean[]) probes.invoke(null, CUSTOMER);
            r.put("B5.name.probeAfterWaitAndCall", Boolean.valueOf(p[idxName]));
        }

        // ------------------------------------------------------------------
        // Part C — the same, on a class ax-agent auto-stripped during traffic
        // ------------------------------------------------------------------
        String auto = firstFullyCoveredClass(probes);
        r.put("C0.autoStripCandidate", auto == null ? "<none>" : auto);
        if (auto != null && obs) {
            Class<?> ac = Class.forName(auto);
            long before = health("classesStripped");
            phase("thirdparty-retransform-autostripped");
            boolean ok = ((Boolean) m(OBSERVER, "retransform", Class.class)
                    .invoke(null, ac)).booleanValue();
            r.put("C1.thirdPartyRetransformed", Boolean.valueOf(ok));
            Thread.sleep(2500L);
            r.put("C2.classesStrippedDelta", Long.valueOf(health("classesStripped") - before));
        }

        r.put("health.transformFailures", Long.valueOf(health("transformFailures")));
        r.put("health.classesInstrumented", Long.valueOf(health("classesInstrumented")));
        r.put("health.classesStripped", Long.valueOf(health("classesStripped")));
        r.put("health.stripFailures", Long.valueOf(health("stripFailures")));
        phase("done");
    }

    /**
     * Does the LIVE class still carry {@code MethodParameters}? Reads the first constructor
     * parameter's {@code isNamePresent()} — exactly what Spring MVC parameter binding and
     * Jackson's {@code ParameterNamesModule} depend on. Measured, not inferred from byte counts.
     */
    private static boolean parameterNamesPresent(Class<?> c) {
        try {
            Constructor<?> ctor = c.getConstructor(String.class, String.class, String.class);
            java.lang.reflect.Parameter[] ps = ctor.getParameters();
            return ps.length > 0 && ps[0].isNamePresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The first demo class whose probes are all set — the population Tier-1b auto-strips. */
    private static String firstFullyCoveredClass(Method probes) throws Exception {
        String[] candidates = {
                "io.auxin.demo.http.handler.HealthHandler",
                "io.auxin.demo.http.handler.OrderHandler",
                "io.auxin.demo.Counters",
                "io.auxin.demo.TrafficDriver",
                "io.auxin.demo.http.DemoServer",
                "io.auxin.demo.service.ShippingService",
                "io.auxin.demo.repo.CustomerRepository",
        };
        for (int i = 0; i < candidates.length; i++) {
            boolean[] p = (boolean[]) probes.invoke(null, candidates[i]);
            if (p == null || p.length == 0) continue;
            boolean all = true;
            for (int j = 0; j < p.length; j++) if (!p[j]) { all = false; break; }
            if (all) return candidates[i];
        }
        return null;
    }

    // =====================================================================================
    // Post-experiment traffic: proves OTel is still instrumenting after a strip + retransform
    // =====================================================================================
    private static int postCheckTraffic() throws Exception {
        Class<?> serverCls = Class.forName("com.sun.net.httpserver.HttpServer");
        Class<?> handlerCls = Class.forName("com.sun.net.httpserver.HttpHandler");
        Class<?> exchangeCls = Class.forName("com.sun.net.httpserver.HttpExchange");

        Object server = serverCls.getMethod("create", InetSocketAddress.class, int.class)
                .invoke(null, new InetSocketAddress("127.0.0.1", 0), Integer.valueOf(0));
        Object handler = java.lang.reflect.Proxy.newProxyInstance(
                Harness.class.getClassLoader(), new Class<?>[]{handlerCls},
                new PostCheckHandler(exchangeCls));
        serverCls.getMethod("createContext", String.class, handlerCls)
                .invoke(server, POST_CHECK_PATH, handler);
        serverCls.getMethod("start").invoke(server);
        Object addr = serverCls.getMethod("getAddress").invoke(server);
        int port = ((Integer) addr.getClass().getMethod("getPort").invoke(addr)).intValue();

        int ok = 0;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + POST_CHECK_PATH + "?i=" + i).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(4000);
            if (c.getResponseCode() == 200) ok++;
            InputStream in = c.getInputStream();
            byte[] b = new byte[64];
            while (in.read(b) > 0) { /* drain */ }
            in.close();
            c.disconnect();
        }
        serverCls.getMethod("stop", int.class).invoke(server, Integer.valueOf(0));
        System.out.println("[g5] post-check traffic: " + ok + "/5 ok on " + POST_CHECK_PATH);
        return ok;
    }

    private static final class PostCheckHandler implements java.lang.reflect.InvocationHandler {
        private final Class<?> exchangeCls;

        PostCheckHandler(Class<?> exchangeCls) { this.exchangeCls = exchangeCls; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"handle".equals(method.getName())) return null;
            Object ex = args[0];
            byte[] body = "g5postcheck-ok".getBytes("UTF-8");
            exchangeCls.getMethod("sendResponseHeaders", int.class, long.class)
                    .invoke(ex, Integer.valueOf(200), Long.valueOf(body.length));
            OutputStream os = (OutputStream) exchangeCls.getMethod("getResponseBody").invoke(ex);
            os.write(body);
            os.close();
            return null;
        }
    }

    // =====================================================================================
    // JaCoCo: in-process dump through the agent's own public API
    // =====================================================================================
    private static void dumpJacoco(String path) {
        if (!present(JACOCO_RT)) {
            System.out.println("[g5] JaCoCo RT not present: no mid-run dump");
            return;
        }
        try {
            Object agent = Class.forName(JACOCO_RT).getMethod("getAgent").invoke(null);
            byte[] data = (byte[]) agent.getClass().getMethod("getExecutionData", boolean.class)
                    .invoke(agent, Boolean.FALSE);
            FileOutputStream out = new FileOutputStream(path);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            System.out.println("[g5] wrote " + path + " (" + data.length + " bytes, JaCoCo "
                    + agent.getClass().getMethod("getVersion").invoke(agent) + ")");
        } catch (Throwable t) {
            System.out.println("[g5] JaCoCo mid-run dump FAILED: " + t);
        }
    }

    // ---------------- tiny helpers ----------------

    private static void phase(String p) {
        try {
            if (present(OBSERVER)) m(OBSERVER, "setPhase", String.class).invoke(null, p);
        } catch (Throwable ignored) {
        }
    }

    private static long health(String getter) {
        try {
            return ((Long) m(HEALTH, getter).invoke(null)).longValue();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static boolean gtActive() {
        try {
            return present(AGENT) && ((Boolean) m(AGENT, "active").invoke(null)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean present(String cn) {
        try {
            Class.forName(cn);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method m(String cn, String name, Class<?>... types) throws Exception {
        Method mm = Class.forName(cn).getMethod(name, types);
        mm.setAccessible(true);
        return mm;
    }

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(name + "=")) return args[i].substring(name.length() + 1);
        }
        return def;
    }

    private static void write(String path, String content) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
        try {
            w.write(content);
        } finally {
            w.close();
        }
    }

    private static String json(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  \"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v instanceof Boolean || v instanceof Number) sb.append(v);
            else sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("\n}\n").toString();
    }

    private Harness() {
        throw new AssertionError();
    }
}
