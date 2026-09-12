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
 *   <li>Tier-1b's AUTOMATIC strip fires on a class carrying a C51-exempt method, i.e. it is
 *       gated on the probes that were INSTALLED and not on every slot of the probe array
 *       ({@link #autoStrip()}). The whole-array gate was unsatisfiable for such a class for the
 *       life of the JVM, so its probes never came off the hot path; every class in this fixture
 *       has one, and the only strips previously under test were forced ones</li>
 *   <li>the pre-55 field fallback works on a class file compiled with --release 8</li>
 *   <li>tier-2 counts calls, classifies errors and fills latency buckets</li>
 *   <li>the wire body matches CONTRACTS section 2, gzipped, over HTTP</li>
 *   <li>C50: livenessEvidence is false unless the JVM is explicitly production-classified</li>
 *   <li>SCOPE-v3: the sampled runtime call-edge tier — edges recorded for a sampled root, ZERO
 *       edges when {@code ax.edges.enabled=false}, the depth and per-root caps enforced and
 *       counted, recursion bounded, no trace left open when an exception escapes a root, and
 *       Tier-1b independent of the edge tier in both directions ({@link #edgeTier()})</li>
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
        // `new SmokeTarget()` RAN, and it still carries no probe: its body is
        // `aload_0; iconst_0; invokespecial this(int); return` -- a delegating constructor, which
        // ax-static classifies as dynamicallyObservable=false (the TOSEM 2022 shape named in C51,
        // and the shape of essentially every custom exception type). The agent honours the flag,
        // so this is the C51 contract holding on a constructor rather than on an accessor.
        // The stand-in generator this harness used to run never produced that flag for a
        // constructor -- its C51 rule only caught a two-instruction constant return -- so this
        // assertion used to read `probe(...)`, asserting the opposite of what production does.
        check(!probe(p, "<init>", "()V"),
                "<init>() RAN but has no probe: a delegating constructor is C51-exempt");
        check(probe(p, "<init>", "(I)V"),
                "<init>(I) probe set (entry frame declares uninitializedThis, not the class)");
        check(probe(p, "alpha", "(I)I"), "alpha probe set");
        check(probe(p, "gamma", "(D)D"), "gamma probe set");
        check(!probe(p, "beta", "(Ljava/lang/String;)Ljava/lang/String;"), "beta probe NOT set (never called)");
        check(!probe(p, "boundary", "(I)I"), "boundary probe NOT set (never called)");
        // counter() is `aload_0; getfield; lreturn` -- a plain field accessor, so it is C51-exempt
        // under the real generator and would have no probe even if it HAD been called. beta() and
        // boundary() above are the assertions that still carry the "never called" case.
        check(!probe(p, "counter", "()J"),
                "counter probe NOT set (a plain field accessor is C51-exempt)");

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
        // Not called YET: autoStrip() calls it later, on purpose, to make every INSTALLED probe
        // of this class set while its two C51-exempt slots stay zero for ever.
        check(!probeOf(up, "io.auxin.userapp.Thing", "idle", "(I)I"),
                "userapp idle() probe NOT set (not called yet)");
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

        autoStrip();

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
        // compute() is what proves the class-file-52 path works: the agent had to synthesise a
        // <clinit> and a static field for it, because condy needs class file >= 55.
        check(probeOf(lp, "smoke.LegacyTarget", "compute", "(I)I"), "legacy compute probe set");
        // LegacyTarget's constructor is javac's implicit `super()` -- `aload_0; invokespecial
        // Object.<init>; return` -- so the real generator marks it dynamicallyObservable=false
        // and the agent emits no probe for it. This used to assert the probe WAS set, against a
        // generator whose C51 rule did not recognise a delegating constructor.
        check(!probeOf(lp, "smoke.LegacyTarget", "<init>", "()V"),
                "legacy <init>() RAN but has no probe: implicit super() is C51-exempt");
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

        edgeTier();

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
        // SCOPE-v3: additive, alongside coverage and tier2, on every window whatever the tier's
        // state. schemaVersion stays 2 precisely because it is additive -- the collector refuses
        // any version it does not know exactly, so bumping it would drop the coverage too.
        check(Json.asArray(w.get("edges")) != null,
                "edges array present alongside coverage and tier2 (CONTRACTS section 2)");
        Map<String, Object> covTarget = findCoverage(cov, "smoke.SmokeTarget");
        check(covTarget != null, "coverage entry for smoke.SmokeTarget");
        check(Json.str(covTarget, "schemaHash", "").length() == 64, "coverage carries the schemaHash");
        check(Json.str(covTarget, "probes", "").length() > 0, "coverage carries the base64 bitset");
        check(bitsetMatches(Json.str(covTarget, "probes", ""), p2), "base64 bitset matches the live array");

        // C51 requires a not-dynamically-observable method to stay distinguishable from a
        // de-instrumented one, and `probes` alone cannot do it: a slot with no probe is never
        // written by anything, so its zero is silence, not an observation. The installed-probe
        // mask -- the same one that gates the Tier-1b strip -- ships beside the bitset so a bit
        // nothing in the JVM can set is never readable as "this method never ran".
        boolean[] instMask = AuxinAgent.installedProbes("smoke.SmokeTarget");
        check(instMask != null && instMask.length == p2.length,
                "an installed-probe mask exists for smoke.SmokeTarget, probe-array-sized");
        check(bitsetMatches(Json.str(covTarget, "probesInstalled", ""), instMask),
                "coverage[].probesInstalled on the wire matches the live mask");
        check(!instMask[AuxinAgent.probeIndex("smoke.SmokeTarget", "answer", "()I")]
                        && !instMask[AuxinAgent.probeIndex("smoke.SmokeTarget", "counter", "()J")]
                        && !instMask[AuxinAgent.probeIndex("smoke.SmokeTarget", "<init>", "()V")],
                "the mask is ZERO at all three C51-exempt indices, so their permanently-unset "
                        + "coverage bits cannot be shipped as evidence that a method never ran");
        check(instMask[AuxinAgent.probeIndex("smoke.SmokeTarget", "logSize", "()I")],
                "…and ONE at logSize(), which really was never called: its zero coverage bit IS "
                        + "evidence of death, and the mask is what tells the two apart");

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
        check(Json.num(health, "stripMaskMissing", -1) == 0,
                "stripMaskMissing == 0 (every Tier-1b decision in this run had a usable "
                        + "installed-probe mask; a missing one leaves the probes installed)");
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

    /**
     * Tier-1b's AUTOMATIC strip, on a class that carries a C51-exempt method.
     *
     * <p><b>The bug this pins.</b> The strip gate used to be "every bit of the probe array is
     * set". The array is sized to every probe-eligible method in the build manifest, but a probe
     * is installed at only some of those indices — C51 exempts a method that cannot be covered
     * dynamically, so nothing ever writes its slot. The gate was therefore <b>unsatisfiable for
     * the life of the JVM</b> for any class holding one such method: it was never a candidate,
     * its probes stayed on the hot path, and the headline "steady-state overhead reaches zero"
     * was false for it. <b>Every</b> class in this fixture has at least one C51-exempt method,
     * and so did 13 of the 20 probed classes on the G5 demo application — the reason the suite
     * never noticed is that every strip it tested was a FORCED one ({@code stripNow}), which
     * bypasses the gate entirely.
     *
     * <p>{@code io.auxin.userapp.Thing} is the subject: 4 manifest indices, of which
     * {@code <init>()} (javac's implicit {@code super()}) and {@code calls()} (a plain field
     * accessor) are C51-exempt, so probes exist only at {@code work()} and {@code idle()}. Both
     * run here, so every INSTALLED probe is set while the two exempt bits stay zero — the exact
     * state the old gate could not strip and the new one must.
     *
     * <p>Nothing in this method calls {@code stripNow}. The drain thread has to find it.
     */
    private static void autoStrip() throws Exception {
        section("tier-1b: the AUTOMATIC strip is gated on INSTALLED probes, not the whole array");
        final String cls = "io.auxin.userapp.Thing";
        final int workIdx = AuxinAgent.probeIndex(cls, "work", "(I)I");
        final int idleIdx = AuxinAgent.probeIndex(cls, "idle", "(I)I");
        final int initIdx = AuxinAgent.probeIndex(cls, "<init>", "()V");
        final int callsIdx = AuxinAgent.probeIndex(cls, "calls", "()J");
        check(workIdx >= 0 && idleIdx >= 0 && initIdx >= 0 && callsIdx >= 0,
                "all four manifest indices resolved for " + cls);

        boolean[] installed = AuxinAgent.installedProbes(cls);
        check(installed != null && installed.length == 4,
                "an installed-probe mask exists for " + cls + " and is manifest-sized (got "
                        + (installed == null ? "null" : String.valueOf(installed.length)) + ")");
        check(installed[workIdx] && installed[idleIdx],
                "the mask says probes WERE installed at work() and idle()");
        check(!installed[initIdx] && !installed[callsIdx],
                "the mask says NO probe was installed at the two C51-exempt indices");

        // Run every probe-eligible method, exempt ones included, so the only bits left unset are
        // the two that nothing in the JVM is able to write.
        io.auxin.userapp.Thing thing = new io.auxin.userapp.Thing();
        check(thing.work(4) == 13, "Thing.work(4) == 13");
        check(thing.idle(3) == 2, "Thing.idle(3) == 2 (now called, so its probe is set too)");
        check(thing.calls() == 2L, "Thing.calls() == 2 -- the C51-exempt accessor really RAN");

        boolean[] p = AuxinAgent.probes(cls);
        check(p != null && p.length == 4, "probe array present and manifest-sized");
        check(p[workIdx] && p[idleIdx], "every INSTALLED probe of " + cls + " is now set");
        check(!p[initIdx] && !p[callsIdx],
                "both C51-exempt bits are STILL zero after their methods ran -- nothing can ever "
                        + "set them, which is exactly why the whole-array gate was unsatisfiable");
        boolean allBits = true;
        for (int i = 0; i < p.length; i++) if (!p[i]) allBits = false;
        check(!allBits, "the gate Tier-1b used to apply (every bit set) is FALSE for this class "
                + "and would have stayed false for the life of the JVM");

        // The whole point: no stripNow anywhere. The drain thread must pick this up by itself.
        boolean auto = false;
        for (int i = 0; i < 100 && !auto; i++) {
            auto = AuxinAgent.stripped(cls);
            if (!auto) Thread.sleep(50L);
        }
        check(auto, "the drain thread AUTO-stripped " + cls + " with two never-settable bits "
                + "still zero (no stripNow involved; ax.drain.interval.ms=100, waited up to 5s)");

        // "Reported stripped" is not "stripped" -- that conflation IS G5-BUG-3. Clear each bit
        // by hand and call the method again: an already-set probe cannot tell a de-instrumented
        // method from an instrumented one.
        p[idleIdx] = false;
        check(thing.idle(3) == 2, "Thing.idle(3) == 2 after the automatic strip (still callable)");
        check(!p[idleIdx], "idle() ran AFTER the automatic strip and recorded NOTHING");
        p[workIdx] = false;
        check(thing.work(4) == 13, "Thing.work(4) == 13 after the automatic strip");
        check(!p[workIdx], "work() likewise -- this class's steady-state cost really is zero");
        check(thing.calls() == 4L,
                "…and both calls genuinely executed (calls() == 4), so the two assertions above "
                        + "are about a missing probe and not about a method that never ran");
    }

    // ---------------- the runtime call-edge tier (SCOPE-v3) ----------------

    /**
     * Runs in EVERY configuration, which is the point: the same five root invocations are made
     * whether or not {@code ax.edges.enabled} is set, so "zero edges when the tier is off" is an
     * assertion about a JVM that really did execute the call graph, not about a JVM that never
     * reached it.
     *
     * <p>Proves, in order: edges recorded for a sampled root; a root called from inside a trace
     * recorded as an ordinary edge; the depth cap and the per-root cap each enforced and counted;
     * unbounded recursion bounded by the depth cap; an exception escaping a root leaving no trace
     * open (a leaked trace would make every instrumented call in the JVM pay a thread-local
     * read); and Tier-1b and the edge tier independent in both directions.
     */
    private static void edgeTier() {
        section("call-edge tier (SCOPE-v3)");
        // off      : ax.edges.enabled unset/false -- the default, and the zero-edges assertion
        // full     : ax.edges.sample.rate=1, small caps -- every property asserted exactly
        // sampled  : ax.edges.sample.rate=1024 -- the production default's arithmetic asserted
        final String mode = System.getProperty("smoke.edges", "off");
        final boolean on = !"off".equals(mode);
        final int maxDepth = Integer.parseInt(System.getProperty("smoke.edges.maxDepth", "32"));
        final int maxPerRoot = Integer.parseInt(System.getProperty("smoke.edges.maxPerRoot", "256"));

        check(AuxinAgent.edgesEnabled() == on,
                "edge tier armed == " + on + " (ax.edges.enabled; ON by default since 2026-09-12)");

        // Every agentHealth counter is cumulative for the JVM, and SmokeTarget.boundary is a
        // boundary method too -- so this section measures DELTAS across itself, not absolutes.
        Map<String, Object> h0 = Json.asObject(
                Json.asObject(Json.parse(AuxinAgent.flushNow())).get("agentHealth"));
        final long roots0 = Json.num(h0, "edgesSampledRoots", 0);
        final long depth0 = Json.num(h0, "edgesTruncatedDepth", 0);
        final long perRoot0 = Json.num(h0, "edgesTruncatedRoot", 0);

        EdgeTarget e = new EdgeTarget();
        check(e.root(3) == 18, "EdgeTarget.root(3) == 18");
        check(e.outerRoot(3) == 19, "outerRoot(3) == 19 (a boundary method calling a boundary method)");
        check(e.deepRoot(100) == 100, "deepRoot(100) == 100 (100 recursive frames under one root)");
        check(e.fanRoot(50) == 2450, "fanRoot(50) == 2450 (50 calls at depth one)");
        try {
            e.throwingRoot(1);
            check(false, "throwingRoot(1) should throw");
        } catch (IllegalStateException expected) {
            check(true, "throwingRoot(1) still throws IllegalStateException through the edge handler");
        }
        check(AuxinAgent.edgeActiveTraces() == 0,
                "NO edge trace left open after an exception escaped a root (got "
                        + AuxinAgent.edgeActiveTraces() + "); a leaked trace would make every "
                        + "instrumented call in this JVM pay a thread-local read for ever");

        final String w1 = AuxinAgent.flushNow();

        if (!on) {
            check(edgeArray(w1) != null,
                    "the edges array is on the wire even when the tier is off (an absent key and "
                            + "an empty graph are different facts)");
            check(edgeArray(w1).isEmpty(),
                    "ZERO edges recorded with ax.edges.enabled=false, after five root invocations "
                            + "and " + e.calls() + " instrumented calls");
            Map<String, Object> h = Json.asObject(Json.asObject(Json.parse(w1)).get("agentHealth"));
            check(Boolean.FALSE.equals(h.get("edgesEnabled")), "agentHealth.edgesEnabled == false");
            check(Json.num(h, "edgesSampledRoots", -1) == 0, "edgesSampledRoots == 0");
            check(Json.num(h, "edgesRecorded", -1) == 0, "edgesRecorded == 0");
            check(Json.num(h, "edgesDropped", -1) == 0, "edgesDropped == 0");
            check(AuxinAgent.edgeIdsRegistered() == 0,
                    "not one method carries edge instrumentation (nothing to strip, nothing to run)");
            return;
        }

        if ("sampled".equals(mode)) {
            // The production default. The sampling counter is one per thread across all roots,
            // so in ANY 4096 consecutive root entries on this thread exactly four satisfy
            // (++n & 1023) == 0 -- the rate is arithmetic, not chance, and this asserts it
            // rather than tolerating a range.
            Map<String, Object> hb = Json.asObject(Json.asObject(Json.parse(w1)).get("agentHealth"));
            check(Json.num(hb, "edgesSampleRate", -1) == 1024,
                    "agentHealth.edgesSampleRate == 1024 (the default)");
            final long before = Json.num(hb, "edgesSampledRoots", -1);
            for (int i = 0; i < 4096; i++) {
                if (e.root(1) != 10) {
                    check(false, "root(1) == 10 under sampling");
                    break;
                }
            }
            final String ws = AuxinAgent.flushNow();
            Map<String, Object> ha = Json.asObject(Json.asObject(Json.parse(ws)).get("agentHealth"));
            final long sampled = Json.num(ha, "edgesSampledRoots", -1) - before;
            check(sampled == 4, "4096 root entries at 1-in-1024 sampled EXACTLY 4 of them (got "
                    + sampled + ")");
            check(edge(ws, "root", "level1") == 4,
                    "and recorded the chain once per sampled root, not once per call: root->level1 = "
                            + edge(ws, "root", "level1") + " for 4096 invocations");
            check(edge(ws, "level3", "leaf") == 4, "the deepest edge of the chain, likewise");
            check(AuxinAgent.edgeActiveTraces() == 0, "no trace open after 4096 root invocations");
            return;
        }

        check(edge(w1, "root", "level1") == 2,
                "edge root->level1 recorded twice, once per root invocation (got "
                        + edge(w1, "root", "level1") + ")");
        check(edge(w1, "level1", "level2") == 2 && edge(w1, "level2", "level3") == 2
                        && edge(w1, "level3", "leaf") == 2,
                "the whole four-deep chain was recorded, not just the root's own call");
        check(edge(w1, "root", "leaf") == 2, "a second edge out of the same caller is distinct");
        check(edge(w1, "outerRoot", "root") == 1,
                "a root called from inside a sampled trace is recorded as an ordinary edge");
        check(edge(w1, "throwingRoot", "thrower") == 1,
                "the edge into the callee that threw was still recorded");

        // Depth cap. deepRoot sits at index 0 and recurse fills indices 1..maxDepth-1, so the
        // trace records exactly maxDepth-1 edges out of 100 recursive calls.
        final long deep = edge(w1, "deepRoot", "recurse") + edge(w1, "recurse", "recurse");
        check(deep == maxDepth - 1,
                "recursion BOUNDED: 100 recursive calls produced " + deep + " edges, the depth "
                        + "cap's maximum of " + (maxDepth - 1));
        check(edge(w1, "recurse", "recurse") == maxDepth - 2,
                "the self edge is counted once per recorded frame (got "
                        + edge(w1, "recurse", "recurse") + ", expected " + (maxDepth - 2) + ")");

        // Per-root cap, at depth one, so the depth cap is not what stopped it.
        check(edge(w1, "fanRoot", "leaf") == maxPerRoot,
                "per-root cap ENFORCED: 50 calls at depth one produced exactly ax.edges.max.per."
                        + "root = " + maxPerRoot + " edges (got " + edge(w1, "fanRoot", "leaf") + ")");

        Map<String, Object> h1 = Json.asObject(Json.asObject(Json.parse(w1)).get("agentHealth"));
        check(Boolean.TRUE.equals(h1.get("edgesEnabled")), "agentHealth.edgesEnabled == true");
        check(Json.num(h1, "edgesSampleRate", -1) == 1,
                "agentHealth.edgesSampleRate == 1 (the counts above are only interpretable with it)");
        check(Json.num(h1, "edgesSampledRoots", -1) - roots0 == 5,
                "five root invocations sampled in this section; the nested root is not a sixth "
                        + "(got " + (Json.num(h1, "edgesSampledRoots", -1) - roots0) + ")");
        check(Json.num(h1, "edgesTruncatedDepth", -1) - depth0 == 1,
                "the depth cap was COUNTED, once for the root invocation that hit it");
        check(Json.num(h1, "edgesTruncatedRoot", -1) - perRoot0 == 1,
                "the per-root cap was COUNTED, once for the root invocation that hit it");
        check(Json.num(h1, "edgesDropped", -1) == 0, "no edge was dropped by the ring");
        check(Json.num(h1, "edgeTierFailures", -1) == 0,
                "the edge tier never failed open (a single Throwable would latch it off)");
        check(Json.num(h1, "edgeTracesReaped", -1) == 0, "no trace had to be reaped");
        check(Json.num(h1, "transformFailures", -1) == 0, "no transform failed with edges on");

        // ---- Tier-1b and the edge tier are independent, in both directions ----
        int level1Idx = AuxinAgent.probeIndex("smoke.EdgeTarget", "level1", "(I)I");
        boolean[] ep = AuxinAgent.probes("smoke.EdgeTarget");
        check(ep != null && ep[level1Idx], "EdgeTarget.level1 has a probe and it is set");
        check(AuxinAgent.stripNow(EdgeTarget.class),
                "Tier-1b still removes probes from a class carrying edge instrumentation "
                        + "(the stripper matches probe shapes, and an edge call is not one)");
        boolean[] ep2 = AuxinAgent.probes("smoke.EdgeTarget");
        ep2[level1Idx] = false;

        final String w2 = AuxinAgent.flushNow();     // separates the post-strip window
        check(e.strippedRoot(3) == 12, "strippedRoot(3) == 12 after the strip");
        final String w3 = AuxinAgent.flushNow();
        check(!ep2[level1Idx],
                "level1 ran after the strip and recorded NO coverage (its probe really is gone)");
        check(edge(w3, "strippedRoot", "level1") == 1 && edge(w3, "level1", "level2") == 1,
                "...and the SAME call still recorded its edges: stripping a class's probes does "
                        + "not break edge recording");
        check(edgeArray(w2).isEmpty(),
                "the window between the two calls carries no edges (counts are per window, "
                        + "additive, and not double counted)");
        check(AuxinAgent.edgeActiveTraces() == 0, "no trace open at the end of the edge section");
    }

    private static List<Object> edgeArray(String body) {
        List<Object> a = Json.asArray(Json.asObject(Json.parse(body)).get("edges"));
        return a == null ? new ArrayList<Object>() : a;
    }

    /** @return the count for one {@code EdgeTarget} edge in this window; 0 when absent. */
    private static long edge(String body, String from, String to) {
        final String cls = "smoke.EdgeTarget";
        final int fromIdx = AuxinAgent.probeIndex(cls, from, "(I)I");
        final int toIdx = AuxinAgent.probeIndex(cls, to, "(I)I");
        long n = 0;
        List<Object> edges = edgeArray(body);
        for (int i = 0; i < edges.size(); i++) {
            Map<String, Object> x = Json.asObject(edges.get(i));
            if (!cls.equals(Json.str(x, "fromClass", "")) || Json.num(x, "fromIdx", -1) != fromIdx) continue;
            if (!cls.equals(Json.str(x, "toClass", "")) || Json.num(x, "toIdx", -1) != toIdx) continue;
            n += Json.num(x, "count", 0);
        }
        return n;
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
