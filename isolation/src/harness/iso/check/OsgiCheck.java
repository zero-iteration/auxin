package iso.check;

import io.auxin.agent.AuxinAgent;
import io.auxin.agent.health.Health;
import iso.api.Work;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * TEST 2 -- Apache Felix, embedded. A bundle class loader delegates java.* to its parent and
 * everything else only to the bundles it is wired to; the application class path (and therefore
 * the agent jar) is not supposed to be reachable.
 *
 * <p>"Not supposed to be" is doing real work in that sentence. Felix ships
 * {@code felix.bootdelegation.implicit=true} BY DEFAULT: when a bundle class load misses, Felix
 * inspects the call stack and, if the caller is not itself bundle code, quietly delegates to the
 * caller's class loader. That silently exposes a {@code -javaagent} on the application class path
 * to every bundle in the framework. So the suite runs four configurations:
 *
 * <ul>
 *   <li>{@code implicit} -- Felix's out-of-the-box defaults. The agent is expected to be VISIBLE
 *       here, through implicit boot delegation, and the ordinary condy probe is used.</li>
 *   <li>{@code strict} -- {@code felix.bootdelegation.implicit=false} and no boot delegation of
 *       any kind. THIS is the real isolation test and the one that decides A10's claim that the
 *       java.lang bridge makes {@code org.osgi.framework.bootdelegation} unnecessary.</li>
 *   <li>{@code bootdelegation} -- strict PLUS
 *       {@code org.osgi.framework.bootdelegation=io.auxin.*}. Boot delegation sends the
 *       request to the BOOT loader, which has never heard of an agent jar on the application
 *       class path, so this must STILL not make the agent visible -- i.e. the property operators
 *       reach for first does not actually fix anything.</li>
 *   <li>{@code bootdelegation-app} -- bootdelegation PLUS
 *       {@code org.osgi.framework.bundle.parent=app}: the combination that genuinely does expose
 *       the agent to bundles.</li>
 * </ul>
 */
public final class OsgiCheck {

    public static void main(String[] args) throws Exception {
        String scenario = System.getProperty("iso.mode", "default");
        // A "-fieldshape" suffix configures Felix exactly like the mode it is derived from but
        // runs the agent with ax.bridge.shape=field, i.e. the pre-F2 bridge. It exists so the
        // rollback switch is TESTED in the container F2 is about, not merely available.
        final boolean fieldShape = scenario.endsWith("-fieldshape");
        final String mode = fieldShape
                ? scenario.substring(0, scenario.length() - "-fieldshape".length()) : scenario;
        Checks c = new Checks("osgi-felix-" + scenario);

        Map<String, String> config = new HashMap<String, String>();
        config.put(Constants.FRAMEWORK_STORAGE, System.getProperty("iso.felixCache"));
        config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        // the service contract, shared between the framework side and the bundle
        config.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, "iso.api");
        if (!"implicit".equals(mode)) {
            // Felix-specific: stop it guessing that a non-bundle caller means "delegate to the
            // caller's loader". Without this there is no OSGi isolation to test.
            config.put("felix.bootdelegation.implicit", "false");
        }
        if (mode.startsWith("bootdelegation")) {
            config.put(Constants.FRAMEWORK_BOOTDELEGATION, "io.auxin.*");
        }
        if ("bootdelegation-app".equals(mode)) {
            config.put(Constants.FRAMEWORK_BUNDLE_PARENT, Constants.FRAMEWORK_BUNDLE_PARENT_APP);
        }

        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        c.info("FrameworkFactory = " + factory.getClass().getName());
        Framework framework = factory.newFramework(config);
        framework.init();
        framework.start();
        c.check(framework.getState() == Bundle.ACTIVE, "Felix framework started");
        c.info("framework = " + framework.getSymbolicName() + " " + framework.getVersion()
                + " bootdelegation=" + config.get(Constants.FRAMEWORK_BOOTDELEGATION)
                + " bundle.parent=" + config.get(Constants.FRAMEWORK_BUNDLE_PARENT)
                + " felix.bootdelegation.implicit=" + config.get("felix.bootdelegation.implicit"));

        BundleContext ctx = framework.getBundleContext();
        File jar = new File(System.getProperty("iso.bundleJar"));
        Bundle bundle = ctx.installBundle(jar.toURI().toString());
        try {
            bundle.start();
        } catch (Throwable e) {
            c.failed("bundle.start() failed -- instrumented code broke inside OSGi", e);
            c.report();
            return;
        }
        c.check(bundle.getState() == Bundle.ACTIVE, "test bundle started (activator ran)");

        ServiceReference<?> ref = ctx.getServiceReference(Work.class.getName());
        c.check(ref != null, "the bundle registered its iso.api.Work service");
        Work work = (Work) ctx.getService(ref);

        Class<?> implClass = work.getClass();
        ClassLoader bundleLoader = implClass.getClassLoader();
        c.info("impl=" + implClass.getName() + " loader=" + bundleLoader);
        c.check(bundleLoader != ClassLoader.getSystemClassLoader()
                        && bundleLoader != null
                        && bundleLoader.getClass().getName().startsWith("org.apache.felix"),
                "iso.osgi.Service was defined by a Felix bundle class loader");

        // F1 FIXED: 'implicit' moved from the condy path to the bridge path. Only
        // 'bootdelegation-app' -- where the parent chain genuinely reaches the agent loader --
        // still takes condy. Deriving this from the mode name is what made the expectation stale
        // when the agent's behaviour changed; assert on OBSERVED shape as well so the next
        // behaviour change fails loudly instead of silently re-classifying itself.
        final boolean bridgePath = !"bootdelegation-app".equals(mode);
        boolean agentVisibleFromBundle;
        try {
            Class<?> seen = Class.forName("io.auxin.agent.runtime.ProbeHolder", false, bundleLoader);
            agentVisibleFromBundle = seen == io.auxin.agent.runtime.ProbeHolder.class;
        } catch (Throwable expected) {
            agentVisibleFromBundle = false;
        }
        c.info("bundle loader can resolve the agent's ProbeHolder? " + agentVisibleFromBundle);
        StringBuilder chain = new StringBuilder();
        boolean reachesAgentLoader = false;
        for (ClassLoader l = bundleLoader; l != null; l = l.getParent()) {
            chain.append(l).append(" -> ");
            if (l == io.auxin.agent.runtime.ProbeHolder.class.getClassLoader()) reachesAgentLoader = true;
        }
        chain.append("bootstrap");
        c.info("bundle loader parent chain: " + chain);
        // The parent chain is the same answer in all four modes as the one the INSTRUMENTED CLASS
        // gets at link time; the Class.forName probe is not (see 'implicit'). A visibility rule of
        // "identity-equal AND structurally reachable" would be right in every configuration here.
        c.check(reachesAgentLoader == "bootdelegation-app".equals(mode),
                "the bundle loader's PARENT CHAIN reaches the agent's loader only in "
                        + "bundle.parent=app (chain says " + reachesAgentLoader + ") -- structural "
                        + "delegation, unlike a Class.forName probe, is not stack-dependent and "
                        + "classifies this loader correctly in every mode");
        if ("bootdelegation-app".equals(mode)) {
            c.check(agentVisibleFromBundle,
                    "bootdelegation + bundle.parent=app DOES expose the agent to the bundle");
        } else if ("bootdelegation".equals(mode)) {
            c.check(!agentVisibleFromBundle,
                    "org.osgi.framework.bootdelegation=io.auxin.* alone does NOT expose the "
                            + "agent: it delegates to the BOOT loader, which has no agent jar");
        } else if ("implicit".equals(mode)) {
            c.check(agentVisibleFromBundle,
                    "Felix's DEFAULT felix.bootdelegation.implicit=true exposes a class-path "
                            + "agent to every bundle (no OSGi isolation at all out of the box)");
        } else {
            c.check(!agentVisibleFromBundle,
                    "strict OSGi: the bundle cannot resolve the agent at all");
        }

        // ---- drive the instrumented bundle class ----
        // In 'implicit' mode the agent was TOLD the bundle can see it (see above), so it emitted
        // a direct io.auxin reference. Whether that resolves depends on the call stack.
        Throwable alphaFailure = null;
        int alpha = -1;
        try {
            alpha = work.alpha(10);
        } catch (Throwable e) {
            alphaFailure = Checks.root(e);
        }
        if ("implicit".equals(mode)) {
            c.info("alpha() from an application thread threw: " + alphaFailure);
            // Same loader, same package, same JVM -- but asked by APPLICATION code this time.
            // Felix's implicit boot delegation walks the call stack, so the answer depends on
            // who is asking. This is why LoaderVisibility's probe cannot predict what the
            // INSTRUMENTED CLASS will get when the JVM resolves the same name on its behalf.
            String whenAppAsks;
            try {
                Class<?> t2 = Class.forName("io.auxin.agent.runtime.Tier2Runtime", false, bundleLoader);
                whenAppAsks = "FOUND (" + t2.getClassLoader() + ")";
            } catch (Throwable e) {
                whenAppAsks = "not found: " + Checks.root(e);
            }
            c.info("the SAME bundle loader asked for Tier2Runtime by APPLICATION code: " + whenAppAsks);
            c.check(whenAppAsks.startsWith("FOUND"),
                    "SAME LOADER, DIFFERENT ANSWER: the bundle loader resolves Tier2Runtime for "
                            + "application code and refuses it for the bundle's own code. A "
                            + "visibility probe run from the agent's stack cannot predict linkage "
                            + "inside the instrumented class.");
            // F1 FIXED. This assertion previously encoded the DEFECT: under Felix's default
            // config the agent emitted a direct io.auxin Tier2Runtime call into a bundle
            // class and it threw ClassNotFoundException inside application code. LoaderVisibility
            // now proves visibility structurally (parent-chain walk AND Class identity) instead of
            // by Class.forName on the agent's own stack, so the class is bridged and tier-2
            // degrades via tier2NotBridgeable. The asymmetry asserted just above is still REAL --
            // it is the reason the old probe was wrong -- so that check stays.
            c.check(alphaFailure == null,
                    "F1 FIXED: nothing escapes into application code under Felix default config "
                            + "(alpha() threw: " + alphaFailure + ")");
        } else {
            if (alphaFailure != null) {
                c.failed("the instrumented bundle class threw when called", alphaFailure);
                c.report();
                return;
            }
            c.check(alpha == 47, "Service.alpha(10) == 47 inside the bundle (got " + alpha + ")");
        }

        String beta = work.beta("hello");
        c.check("HELLO:5".equals(beta), "Service.beta(\"hello\") == HELLO:5 (got " + beta + ")");
        c.info("service reports its loader as " + work.where());
        int viaIface = work.viaInterface(5);
        c.check(viaIface == 25, "Service.viaInterface(5) == 25 (interface default + static, got "
                + viaIface + ")");

        // ---- and from a thread the BUNDLE started, with no application frames on the stack ----
        String worker = work.workerOutcome();
        c.info("bundle's own thread, first touch of iso.osgi.Worker: " + worker);
        if ("implicit".equals(mode)) {
            c.check(true, "bundle-own-thread outcome recorded for the report: " + worker);
        } else {
            c.check("OK".equals(worker),
                    "a thread the BUNDLE started ran an instrumented class correctly (" + worker + ")");
        }

        boolean[] p = AuxinAgent.probes("iso.osgi.Service");
        c.info("probes(iso.osgi.Service)=" + Checks.bits(p)
                + " bridge=" + AuxinAgent.bridgeStatus() + " skipped=" + Health.skipped());
        c.check(p != null, "probe array exists for the bundle's class");
        if (p != null) {
            c.check(p[idx("<init>", "()V")], "<init> probe SET (the activator built one)");
            c.check(p[idx("alpha", "(I)I")], "alpha probe SET");
            c.check(p[idx("beta", "(Ljava/lang/String;)Ljava/lang/String;")], "beta probe SET");
            c.check(p[idx("where", "()Ljava/lang/String;")], "where() probe SET");
            c.check(!p[idx("never", "(I)I")], "never() probe NOT set");
        }
        c.check(Health.transformFailures() == 0, "zero transform failures");
        c.check(!Health.skipped().containsKey(Health.SKIP_AGENT_NOT_VISIBLE),
                "no bundle class was skipped for agentNotVisible");

        // ---- what the BRIDGE path costs, measured rather than argued ----
        // (a) Tier-1b de-instrumentation. DrainThread.stripCoveredClasses() can only retransform
        //     a class it holds a Class object for, and that handle is only ever available inside
        //     condy resolution (lookup.lookupClass()).
        Class<?> stripHandle = io.auxin.agent.runtime.ProbeHolder.loadedClass("iso.osgi.Service");
        c.info("strip handle for iso.osgi.Service = " + stripHandle);
        // (b) Interfaces. A field-based path cannot instrument one; a condy-based path can.
        boolean[] hp = AuxinAgent.probes("iso.osgi.Helper");
        Map<String, Long> skips = Health.skipped();
        c.info("probes(iso.osgi.Helper)=" + Checks.bits(hp) + " skips=" + skips);
        // (c) Tier 2. Tier2Runtime is a direct call and no java.lang trick can carry one.
        boolean tier2Degraded = skips.containsKey(Health.SKIP_TIER2_NOT_BRIDGEABLE);

        // F1 FIXED: 'implicit' used to be exempted here because the agent mispredicted
        // visibility in that mode and the run was already failing. It now takes the bridge
        // correctly, so it MUST be held to the same bridge-path expectations as every other
        // bridged mode -- an exemption kept past its cause is how a regression hides.
        //
        // F2 FIXED: the bridge no longer abandons condy. Its bootstrap method is now a synthetic
        // private static $axInit on the instrumented class ITSELF, which reads
        // java.lang.$Auxin internally -- JaCoCo's $jacocoInit shape. So (a) and (b), which
        // used to assert the DEFECT, now assert that the defect is gone. (c) does not change and
        // must not: any equals-based hop allocates an Object[] per invocation, which is not
        // payable on a hot path, so tier 2 is structurally not bridgeable.
        if (bridgePath && fieldShape) {
            c.check(stripHandle == null,
                    "ROLLBACK (ax.bridge.shape=field): no strip handle for the bundle class, so "
                            + "Tier-1b can NEVER de-instrument it -- its probes stay on the hot "
                            + "path for the life of the JVM. This is the F2 defect, asserted in a "
                            + "real OSGi container so the rollback switch's cost is measured.");
            c.check(hp == null && skips.containsKey(Health.SKIP_INTERFACE_NEEDS_FIELD),
                    "ROLLBACK (ax.bridge.shape=field): the bundle's INTERFACE was skipped "
                            + "(interfaceNeedsField); default and static interface methods get no "
                            + "coverage at all");
            c.check(tier2Degraded,
                    "ROLLBACK (ax.bridge.shape=field): tier 2 degraded to tier 1 as it always did");
        } else if (bridgePath) {
            c.check(stripHandle != null,
                    "F2: the BRIDGE path captures a strip handle (" + stripHandle + ") -- Tier-1b "
                            + "can de-instrument a bundle class, so its probes do NOT stay on the "
                            + "hot path for the life of the JVM");
            c.check(hp != null && !skips.containsKey(Health.SKIP_INTERFACE_NEEDS_FIELD),
                    "F2: the bundle's INTERFACE is probed through the bridge (probes="
                            + Checks.bits(hp) + ") -- default and static interface methods now get "
                            + "coverage in OSGi and interfaceNeedsField no longer fires");
            c.check(tier2Degraded,
                    "BRIDGE COST (unchanged by F2): the tier-2 method degraded to tier-1 "
                            + "(tier2NotBridgeable), which is the designed fail-open -- an "
                            + "equals-based hop allocates per invocation and is not payable on a "
                            + "hot path, so this one is structural, not an oversight");
        } else {
            c.check(stripHandle != null,
                    "condy path DOES capture a strip handle (" + stripHandle + ")");
            c.check(hp != null, "condy path DOES probe the interface (probes=" + Checks.bits(hp) + ")");
            c.check(!tier2Degraded, "condy path carries tier-2 into the bundle");
        }

        // The interface's own coverage bits, which did not exist at all before F2.
        if (hp != null) {
            int twice = AuxinAgent.probeIndex("iso.osgi.Helper", "twice", "(I)I");
            int thrice = AuxinAgent.probeIndex("iso.osgi.Helper", "thrice", "(I)I");
            c.check(twice >= 0 && thrice >= 0 && hp[twice] && hp[thrice],
                    "the interface's default AND static method both recorded coverage "
                            + "(twice=" + (twice >= 0 && hp[twice]) + " thrice="
                            + (thrice >= 0 && hp[thrice]) + ")");
        }

        framework.stop();
        framework.waitForStop(5000);
        c.check(true, "framework stopped cleanly");
        c.report();
    }

    private static int idx(String name, String desc) {
        return AuxinAgent.probeIndex("iso.osgi.Service", name, desc);
    }
}
