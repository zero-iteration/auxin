package smoke;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.auxin.agent.AuxinAgent;
import io.auxin.agent.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * GATE G3 — end to end, in a real JVM, with the real agent attached via -javaagent.
 *
 * <p>Proves, in order:
 * <ol>
 *   <li>probes install at class load and flip only for methods that actually ran</li>
 *   <li>the read-then-store probe reuses a stack map frame already at offset 0, and repeat
 *       calls take the IFNE branch (the branch itself is asserted from javap by run-smoke.sh)</li>
 *   <li>{@code java.lang.$Auxin} is defined in the bootstrap loader with exactly one
 *       member, and a class loaded by an agent-invisible loader is still probed through it</li>
 *   <li>a class not in ax.include.packages is never touched (default deny)</li>
 *   <li>C51: a method marked not-dynamically-observable is never probed</li>
 *   <li>retransform strips the probes, and a method called afterwards records NOTHING</li>
 *   <li>every method still works after the strip</li>
 *   <li>the pre-55 field fallback works on a class file compiled with --release 8</li>
 *   <li>tier-2 counts calls, classifies errors and fills latency buckets</li>
 *   <li>the wire body matches CONTRACTS section 2, gzipped, over HTTP</li>
 *   <li>C50: livenessEvidence is false unless the JVM is explicitly production-classified</li>
 * </ol>
 */
public class SmokeApp {

    private static final List<String> BODIES = new ArrayList<String>();
    private static final List<String> HEADERS = new ArrayList<String>();
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("  FAIL  harness threw: " + t);
            t.printStackTrace(System.out);
            failures++;
        }
        System.out.println();
        System.out.println("==== " + (checks - failures) + "/" + checks + " checks passed ====");
        if (failures > 0) {
            System.out.println("==== G3 FAILED: " + failures + " check(s) ====");
            System.out.flush();
            Runtime.getRuntime().halt(1);
        }
        System.out.println("==== G3 PASSED ====");
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static void run(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("smoke.port", "0"));
        boolean expectLiveness = Boolean.parseBoolean(System.getProperty("smoke.expectLiveness", "false"));
        startCollector(port);

        section("agent");
        check(AuxinAgent.active(), "agent is active");
        check(AuxinAgent.manifest() != null, "manifest loaded");

        section("tier-1: install + selective execution");
        SmokeTarget t = new SmokeTarget();
        check(t.alpha(10) == -5, "alpha(10) == -5");
        check(t.gamma(2.0d) == 10.0d, "gamma(2.0) == 10.0");
        check(t.answer() == 42, "answer() == 42");

        boolean[] p = AuxinAgent.probes("smoke.SmokeTarget");
        check(p != null, "probe array exists for smoke.SmokeTarget");
        check(probe(p, "<init>", "()V"), "<init> probe set");
        check(probe(p, "<init>", "(I)V"),
                "<init>(I) probe set (entry frame declares uninitializedThis, not the class)");
        check(probe(p, "alpha", "(I)I"), "alpha probe set");
        check(probe(p, "gamma", "(D)D"), "gamma probe set");
        check(!probe(p, "beta", "(Ljava/lang/String;)Ljava/lang/String;"), "beta probe NOT set (never called)");
        check(!probe(p, "boundary", "(I)I"), "boundary probe NOT set (never called)");
        check(!probe(p, "counter", "()J"), "counter probe NOT set (never called)");

        section("read-then-store probe (G1): shape and idempotence");
        // spin()'s first instruction is already a branch target carrying a stack map frame, so
        // the probe has to REUSE that frame — two entries at one offset is an illegal
        // StackMapTable and the class would not verify.
        check(t.spin(10) == -2, "spin(10) == -2 (probe reused the frame already at offset 0)");
        check(probe(p, "spin", "(I)I"), "spin probe set");
        // Second and third calls take the IFNE branch and never touch the array again. The
        // branch itself is asserted from the bytecode by run-smoke.sh (javap: baload + ifne).
        check(t.spin(10) == -2 && t.spin(4) == -2, "spin still correct on repeat calls");
        check(probe(p, "spin", "(I)I"), "spin probe still set after repeat calls");
        check(t.alpha(10) == -5, "alpha still correct on a repeat call (probe already set)");

        section("C51: not dynamically observable");
        check(!probe(p, "answer", "()I"),
                "answer() was CALLED but has no probe (dynamicallyObservable=false)");

        section("default deny");
        check(new other.Outsider().ping() == 7, "other.Outsider still works");
        check(AuxinAgent.probes("other.Outsider") == null,
                "other.Outsider was never instrumented (outside ax.include.packages)");

        // G5-BUG-1. io.auxin.userapp shares a prefix with the agent's own packages. The
        // shipped ignore list held "io/auxin/", matched it with startsWith, and was
        // consulted BEFORE the include scope, so a customer application under the vendor's
        // package root was silently swallowed whole: classesInstrumented 0, classesSkipped {},
        // transformFailures 0 -- "all of your code is dead". An explicitly named scope must win.
        section("G5-BUG-1: an explicitly scoped package beats the ignore list");
        io.auxin.userapp.Thing thing = new io.auxin.userapp.Thing();
        check(thing.work(4) == 13, "io.auxin.userapp.Thing.work(4) == 13");
        boolean[] up = AuxinAgent.probes("io.auxin.userapp.Thing");
        check(up != null, "io.auxin.userapp.Thing WAS instrumented, even though its package "
                + "shares a prefix with the agent's own");
        check(probeOf(up, "io.auxin.userapp.Thing", "work", "(I)I"),
                "userapp work() probe set");
        check(!probeOf(up, "io.auxin.userapp.Thing", "idle", "(I)I"),
                "userapp idle() probe NOT set (never called)");
        check(AuxinAgent.probes("io.auxin.agent.runtime.ProbeHolder") == null,
                "the agent's own runtime, one package away, is still never instrumented");

        section("tier-1b: de-instrumentation by retransform");
        boolean stripped = AuxinAgent.stripNow(SmokeTarget.class);
        check(stripped, "retransformClasses(SmokeTarget) succeeded");

        String beta = t.beta("hello");
        check("many:5".equals(beta), "beta(\"hello\") == \"many:5\" AFTER strip (got " + beta + ")");
        boolean[] p2 = AuxinAgent.probes("smoke.SmokeTarget");
        check(!probe(p2, "beta", "(Ljava/lang/String;)Ljava/lang/String;"),
                "beta ran after the strip and recorded NOTHING (probe gone)");
        check(probe(p2, "alpha", "(I)I"), "previously recorded coverage survived the strip");

        // spin()'s probe branched to a frame the ORIGINAL code owned. Clearing its bit by hand
        // and calling it again is the only way to prove those instructions really went away —
        // an already-set probe cannot tell a stripped method from an un-stripped one.
        int spinIdx = AuxinAgent.probeIndex("smoke.SmokeTarget", "spin", "(I)I");
        p2[spinIdx] = false;
        check(t.spin(7) == -2, "spin(7) == -2 after the strip (reused frame still valid)");
        check(!p2[spinIdx], "spin's probe was stripped too, without removing the frame it reused");

        section("post-strip: the class still works");
        check(t.alpha(10) == -5, "alpha still correct");
        check(t.gamma(2.0d) == 10.0d, "gamma still correct");
        check(t.answer() == 42, "answer still correct");
        String betaEmpty = t.beta("");
        check("zero!caught:0".equals(betaEmpty),
                "beta exception path still correct (got " + betaEmpty + ")");
        check(t.boundary(3) == 179, "boundary(3) == 179");

        section("pre-55 fallback (class file major 52)");
        check(classFileVersion("smoke/LegacyTarget.class") == 52,
                "LegacyTarget really is class file major 52");
        LegacyTarget legacy = new LegacyTarget();
        check(legacy.compute(5) == 20, "legacy compute(5) == 20");
        check("legacy".equals(legacy.name()), "legacy name() == \"legacy\"");
        boolean[] lp = AuxinAgent.probes("smoke.LegacyTarget");
        check(lp != null, "probe array exists for smoke.LegacyTarget (field fallback)");
        check(probeOf(lp, "smoke.LegacyTarget", "compute", "(I)I"), "legacy compute probe set");
        check(probeOf(lp, "smoke.LegacyTarget", "<init>", "()V"), "legacy <init> probe set");
        check(!probeOf(lp, "smoke.LegacyTarget", "name", "()Ljava/lang/String;"),
                "legacy name() has no probe (dynamicallyObservable=false)");

        section("bootstrap bridge: java.lang.$Auxin");
        check(AuxinAgent.bridgeInstalled(),
                "bridge installed (status=" + AuxinAgent.bridgeStatus() + ")");
        Class<?> bridge = Class.forName("java.lang.$Auxin");
        check(bridge.getClassLoader() == null, "$Auxin was defined by the BOOTSTRAP loader");
        java.lang.reflect.Field[] fields = bridge.getDeclaredFields();
        check(fields.length == 1, "exactly one member (got " + fields.length + ")");
        check("data".equals(fields[0].getName()) && fields[0].getType() == Object.class
                        && java.lang.reflect.Modifier.isStatic(fields[0].getModifiers())
                        && java.lang.reflect.Modifier.isPublic(fields[0].getModifiers()),
                "the member is public static Object data");
        check(bridge.getDeclaredMethods().length == 0
                        && bridge.getDeclaredConstructors().length == 0,
                "no methods and no constructors in java.lang");
        check(fields[0].get(null) != null, "data carries the runtime object");

        // A loader whose parent is the bootstrap loader: OSGi / JBoss Modules / JPMS / fat jar
        // in miniature. It can see java.* and its own classes, and nothing of the agent.
        java.net.URL classes = SmokeApp.class.getProtectionDomain().getCodeSource().getLocation();
        java.net.URLClassLoader isolated =
                new java.net.URLClassLoader(new java.net.URL[]{classes}, null);
        boolean agentInvisible;
        try {
            Class.forName("io.auxin.agent.runtime.ProbeHolder", false, isolated);
            agentInvisible = false;
        } catch (ClassNotFoundException expected) {
            agentInvisible = true;
        }
        check(agentInvisible, "the isolated loader genuinely cannot resolve ProbeHolder");

        Class<?> bt = isolated.loadClass("smoke.BridgeTarget");
        check(bt.getClassLoader() == isolated, "BridgeTarget came from the isolated loader");
        Object instance = bt.getDeclaredConstructor().newInstance();
        Object result = bt.getMethod("work", int.class).invoke(instance, Integer.valueOf(4));
        check(((Integer) result).intValue() == 6,
                "BridgeTarget.work(4) == 6 (class initialised through the bridge)");
        boolean[] bp = AuxinAgent.probes("smoke.BridgeTarget");
        check(bp != null, "probe array exists for the bridge-instrumented class");
        check(probeOf(bp, "smoke.BridgeTarget", "work", "(I)I"),
                "work probe set, recorded via java.lang.$Auxin only");
        check(!probeOf(bp, "smoke.BridgeTarget", "idle", "(I)I"),
                "idle probe NOT set (never called)");
        // work() is on the tier-2 allowlist, but tier-2 calls Tier2Runtime directly and there is
        // no java.lang trick for a hot-path call. It must degrade to tier-1, not to a
        // NoClassDefFoundError — work(4) returning 6 above is that assertion's other half.
        check(!hasTier2For(AuxinAgent.flushNow(), "smoke.BridgeTarget"),
                "no tier-2 entry for the bridge-instrumented class (degraded to tier-1)");

        // F2. The self-BSM bridge keeps condy, with a synthetic $axInit bootstrap method on the
        // instrumented class itself, so lookup.lookupClass() reaches the agent and Tier-1b has a
        // Class handle for a class whose loader cannot see the agent at all. The field + <clinit>
        // shape can never produce one: probes stay on the hot path for the life of the JVM in
        // exactly the containers the bridge exists for. Both shapes are asserted, so the
        // rollback switch is tested rather than merely present.
        boolean selfBsm = !"field".equals(System.getProperty("smoke.bridgeShape", "selfbsm"));
        Class<?> bridgeHandle =
                io.auxin.agent.runtime.ProbeHolder.loadedClass("smoke.BridgeTarget");
        if (selfBsm) {
            check(bridgeHandle == bt,
                    "F2: the BRIDGE path captured a Tier-1b strip handle (got " + bridgeHandle + ")");
            check(AuxinAgent.stripNow(bt),
                    "F2: retransformClasses succeeded on a class whose loader cannot see the agent");
            Object idleResult = bt.getMethod("idle", int.class).invoke(instance, Integer.valueOf(3));
            check(((Integer) idleResult).intValue() == 8,
                    "BridgeTarget.idle(3) == 8 after the bridged class was de-instrumented");
            boolean[] bp2 = AuxinAgent.probes("smoke.BridgeTarget");
            check(!probeOf(bp2, "smoke.BridgeTarget", "idle", "(I)I"),
                    "F2: idle() ran AFTER the strip and recorded NOTHING -- a bridged class "
                            + "reached zero steady-state cost, which the field shape cannot do");
            check(probeOf(bp2, "smoke.BridgeTarget", "work", "(I)I"),
                    "F2: coverage recorded before the strip survived it");
            check(((Integer) bt.getMethod("work", int.class).invoke(instance, Integer.valueOf(4)))
                            .intValue() == 6,
                    "BridgeTarget.work(4) == 6 after the strip (still fully callable)");
        } else {
            check(bridgeHandle == null,
                    "ax.bridge.shape=field: NO strip handle for the bridged class -- Tier-1b can "
                            + "never de-instrument it. This is the F2 defect, asserted so the "
                            + "rollback switch's cost is measured and not merely described.");
            check(((Integer) bt.getMethod("idle", int.class).invoke(instance, Integer.valueOf(3)))
                            .intValue() == 8,
                    "ax.bridge.shape=field: BridgeTarget.idle(3) == 8 (the old shape still works)");
        }

        section("tier-2: counts, errors, latency buckets");
        for (int i = 0; i < 4; i++) t.boundary(2);
        try {
            t.boundary(-1);
            check(false, "boundary(-1) should throw");
        } catch (IllegalStateException expected) {
            check(true, "boundary(-1) still throws IllegalStateException through the probe");
        }
        // 1 call in the post-strip section + 4 here + 1 failed = 6
        long[] tier2 = awaitTier2("smoke.SmokeTarget",
                AuxinAgent.probeIndex("smoke.SmokeTarget", "boundary", "(I)I"), 6, 8000);
        check(tier2[0] == 6, "tier-2 recorded 6 calls (got " + tier2[0] + ")");
        check(tier2[1] == 1, "tier-2 recorded 1 error (got " + tier2[1] + ")");
        check(tier2[2] > 0, "tier-2 latency buckets are populated (got " + tier2[2] + " non-zero)");
        check(tier2[3] == 1, "tier-2 classified the error as IllegalStateException");

        section("wire protocol (CONTRACTS section 2)");
        String body = AuxinAgent.flushNow();
        Map<String, Object> w = Json.asObject(Json.parse(body));
        check(w != null, "flush body is valid JSON");
        check(Json.num(w, "schemaVersion", -1) == 2, "schemaVersion == 2 (CONTRACTS v2)");
        check("smoke001".equals(Json.str(w, "buildSha", "")), "buildSha from the manifest");
        check("smoke-app".equals(Json.str(w, "artifact", "")), "artifact from the manifest");
        check(Json.str(w, "instanceId", "").length() > 0, "instanceId present");
        check(w.containsKey("windowStartMs") && w.containsKey("windowEndMs"), "window bounds present");
        List<Object> loaded = Json.asArray(w.get("classesLoaded"));
        check(loaded != null && loaded.contains("smoke.SmokeTarget"),
                "classesLoaded contains smoke.SmokeTarget");
        check(loaded != null && !loaded.contains("other.Outsider"),
                "classesLoaded does NOT contain the out-of-scope class");
        check(loaded != null && loaded.contains("io.auxin.userapp.Thing"),
                "classesLoaded contains the prefix-colliding userapp class");

        // G5-FINDING-4: classesLoaded is recorded by the transformer before any skip decision,
        // so it is a superset of what we instrumented. Until G5 it WAS the instrumented set,
        // which meant every skip reason also erased the class from "loaded" and C10 could not
        // tell "this pod never loaded the class" from "we declined to instrument it".
        List<Object> instrumented = Json.asArray(w.get("instrumentedClasses"));
        check(instrumented != null, "instrumentedClasses present alongside classesLoaded");
        check(instrumented != null && loaded != null && loaded.containsAll(instrumented),
                "instrumentedClasses is a subset of classesLoaded (" + instrumented.size()
                        + " of " + loaded.size() + ")");
        check(instrumented != null && instrumented.contains("smoke.SmokeTarget"),
                "instrumentedClasses contains smoke.SmokeTarget");
        List<Object> cov = Json.asArray(w.get("coverage"));
        check(cov != null && !cov.isEmpty(), "coverage array present");
        Map<String, Object> covTarget = findCoverage(cov, "smoke.SmokeTarget");
        check(covTarget != null, "coverage entry for smoke.SmokeTarget");
        check(Json.str(covTarget, "schemaHash", "").length() == 64, "coverage carries the schemaHash");
        check(Json.str(covTarget, "probes", "").length() > 0, "coverage carries the base64 bitset");
        check(bitsetMatches(Json.str(covTarget, "probes", ""), p2), "base64 bitset matches the live array");

        Map<String, Object> health = Json.asObject(w.get("agentHealth"));
        check(health != null, "agentHealth present (mandatory)");
        check(Json.num(health, "transformFailures", -1) == 0, "transformFailures == 0");
        check(health.containsKey("ringDropped"), "ringDropped present");
        check(Json.num(health, "clockNs", -1) >= 0, "clockNs reported");
        check(health.containsKey("clockDegraded"), "clockDegraded present");
        check(Boolean.FALSE.equals(health.get("degraded")), "window is not degraded");
        check(Boolean.FALSE.equals(health.get("scopeMatchedNothing")),
                "scopeMatchedNothing == false (G5-BUG-1: 'configured to work and did none' is "
                        + "reported, and this run did work)");
        check(Boolean.FALSE.equals(health.get("classesLoadedTruncated")),
                "classesLoadedTruncated == false (absence from classesLoaded still means "
                        + "'never loaded')");
        // The strip earlier in this run was real, so stripBlocked must be 0 and classesStripped
        // must have moved. G5-BUG-3 was the opposite: classesStripped counting strips that
        // removed nothing, with stripFailures still at zero.
        check(Json.num(health, "classesStripped", 0) >= 1,
                "classesStripped counted the real strip (got "
                        + Json.num(health, "classesStripped", -1) + ")");
        check(Json.num(health, "stripBlocked", -1) == 0,
                "stripBlocked == 0 (no strip reported success while removing nothing)");
        check(Json.num(health, "stripFailures", -1) == 0, "stripFailures == 0");
        Map<String, Object> skipped = Json.asObject(health.get("classesSkipped"));
        check(skipped != null, "classesSkipped present");
        check(Json.num(skipped, "notDynamicallyObservable", 0) >= 2,
                "classesSkipped{notDynamicallyObservable} counted the C51 skips");

        section("C50: test-liveness trap");
        check(Boolean.valueOf(expectLiveness).equals(health.get("livenessEvidence")),
                "livenessEvidence == " + expectLiveness + " (ax.environment "
                        + (expectLiveness ? "is production-classified" : "unset -> fail closed") + ")");
        check(Boolean.FALSE.equals(health.get("testRunnerDetected")), "testRunnerDetected == false");
        check(Json.str(health, "environment", "").length() > 0, "environment reported");
        check(health.get("livenessEvidence") instanceof Boolean, "livenessEvidence is a boolean");
        check(health.get("testRunnerDetected") instanceof Boolean, "testRunnerDetected is a boolean");
        check(!body.contains("p50") && !body.contains("p90") && !body.contains("p99")
                        && !body.contains("percentile"),
                "no percentile anywhere in the payload (C31)");

        section("transport");
        check(!BODIES.isEmpty(), "the collector received at least one POST (" + BODIES.size() + ")");
        check(HEADERS.contains("gzip"), "Content-Encoding: gzip");
        check(HEADERS.contains("application/json"), "Content-Type: application/json");
        Map<String, Object> received = Json.asObject(Json.parse(BODIES.get(BODIES.size() - 1)));
        check(received != null, "the gzipped body the collector received is valid JSON");
    }

    // ---------------- helpers ----------------

    private static boolean probe(boolean[] probes, String name, String desc) {
        return probeOf(probes, "smoke.SmokeTarget", name, desc);
    }

    private static boolean probeOf(boolean[] probes, String cls, String name, String desc) {
        int idx = AuxinAgent.probeIndex(cls, name, desc);
        if (idx < 0 || probes == null || idx >= probes.length) {
            System.out.println("   (no manifest index for " + cls + "#" + name + desc + ")");
            return false;
        }
        return probes[idx];
    }

    /** @return {calls, errors, nonZeroBuckets, illegalStateErrors} summed over every window. */
    private static long[] awaitTier2(String cls, int idx, long expectedCalls, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long[] acc = new long[4];
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            AuxinAgent.flushNow();
            acc = new long[4];
            synchronized (BODIES) {
                for (int i = 0; i < BODIES.size(); i++) {
                    Map<String, Object> w = Json.asObject(Json.parse(BODIES.get(i)));
                    List<Object> t2 = Json.asArray(w.get("tier2"));
                    if (t2 == null) continue;
                    for (int j = 0; j < t2.size(); j++) {
                        Map<String, Object> e = Json.asObject(t2.get(j));
                        if (!cls.equals(Json.str(e, "class", "")) || Json.num(e, "idx", -1) != idx) continue;
                        acc[0] += Json.num(e, "calls", 0);
                        acc[1] += Json.num(e, "errors", 0);
                        List<Object> buckets = Json.asArray(e.get("buckets"));
                        if (buckets != null) {
                            for (int b = 0; b < buckets.size(); b++) {
                                if (((Number) buckets.get(b)).longValue() > 0) acc[2]++;
                            }
                        }
                        Map<String, Object> types = Json.asObject(e.get("errorTypes"));
                        if (types != null) {
                            acc[3] += Json.num(types, "java.lang.IllegalStateException", 0);
                        }
                    }
                }
            }
            if (acc[0] >= expectedCalls) return acc;
        }
        return acc;
    }

    private static boolean hasTier2For(String body, String cls) {
        List<Object> t2 = Json.asArray(Json.asObject(Json.parse(body)).get("tier2"));
        if (t2 == null) return false;
        for (int i = 0; i < t2.size(); i++) {
            if (cls.equals(Json.str(Json.asObject(t2.get(i)), "class", ""))) return true;
        }
        return false;
    }

    private static Map<String, Object> findCoverage(List<Object> coverage, String cls) {
        for (int i = 0; i < coverage.size(); i++) {
            Map<String, Object> c = Json.asObject(coverage.get(i));
            if (cls.equals(Json.str(c, "class", ""))) return c;
        }
        return null;
    }

    private static boolean bitsetMatches(String base64, boolean[] probes) {
        byte[] packed = java.util.Base64.getDecoder().decode(base64);
        for (int i = 0; i < probes.length; i++) {
            boolean bit = (packed[i >>> 3] & (1 << (i & 7))) != 0;
            if (bit != probes[i]) return false;
        }
        return true;
    }

    private static int classFileVersion(String resource) throws Exception {
        InputStream in = SmokeApp.class.getClassLoader().getResourceAsStream(resource);
        try {
            byte[] head = new byte[8];
            if (in.read(head) != 8) return -1;
            return ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
        } finally {
            in.close();
        }
    }

    private static void startCollector(int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/ingest", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws java.io.IOException {
                String enc = ex.getRequestHeaders().getFirst("Content-Encoding");
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream in = "gzip".equals(enc)
                        ? new GZIPInputStream(ex.getRequestBody()) : ex.getRequestBody();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                synchronized (BODIES) {
                    BODIES.add(new String(bos.toByteArray(), "UTF-8"));
                    if (enc != null) HEADERS.add(enc);
                    if (type != null) HEADERS.add(type);
                }
                ex.sendResponseHeaders(202, -1);
                ex.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2,
                new java.util.concurrent.ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread th = new Thread(r, "smoke-collector");
                        th.setDaemon(true);
                        return th;
                    }
                }));
        server.start();
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("-- " + name + " --");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    }
}
