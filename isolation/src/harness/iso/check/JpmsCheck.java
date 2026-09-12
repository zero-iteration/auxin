package iso.check;

import io.auxin.agent.AuxinAgent;
import io.auxin.agent.health.Health;

/**
 * TEST 1b -- a boot-layer named module driven from the class path.
 *
 * <p>The driver lives in the UNNAMED module so it can talk to the agent; the code under test
 * (iso.app.Target) lives in the named module ax.iso.app, resolved with --add-modules. Nothing
 * here weakens the boundary the instrumented class sits behind.
 *
 * <p>The control is ax.iso.sealed: compiled and resolved identically but OUTSIDE
 * ax.include.packages, so the agent never transforms it. Comparing the two modules' read edges
 * is what pins the JDK behaviour down (see RESULTS.md, "why JPMS does not break").
 */
public final class JpmsCheck {

    public static void main(String[] args) throws Exception {
        Checks c = new Checks("jpms-classpath-driver");
        Module unnamed = ClassLoader.getSystemClassLoader().getUnnamedModule();

        Class<?> target = Class.forName("iso.app.Target");
        Module m = target.getModule();
        c.info("instrumented module=" + m.getName() + " named=" + m.isNamed()
                + " loader=" + target.getClassLoader()
                + " exports=" + m.isExported("iso.app") + " opens=" + m.isOpen("iso.app"));
        c.check(m.isNamed() && "ax.iso.app".equals(m.getName()),
                "iso.app.Target is defined in named module ax.iso.app");
        c.check(!m.isOpen("iso.app"), "ax.iso.app does NOT open iso.app (no deep reflection in)");

        // The control module: same module path, same launch, never transformed.
        Module ctl = Class.forName("iso.sealed.Target").getModule();
        c.check(ctl.isNamed() && !ctl.canRead(unnamed),
                "control module ax.iso.sealed (never transformed) does NOT read the unnamed module");
        c.check(m.canRead(unnamed),
                "the TRANSFORMED module reads the unnamed module "
                        + "(jdk.internal.module.Modules.transformedByAgent, added by the VM)");

        int alpha;
        String beta;
        try {
            Object t = target.getDeclaredConstructor().newInstance();
            alpha = ((Integer) target.getMethod("alpha", int.class).invoke(t, Integer.valueOf(10))).intValue();
            beta = (String) target.getMethod("beta", String.class).invoke(t, "hello");
        } catch (Throwable e) {
            c.failed("application code inside the named module threw", e);
            c.report();
            return;
        }
        c.check(alpha == 47, "Target.alpha(10) == 47 (got " + alpha + ")");
        c.check("HELLO:5".equals(beta), "Target.beta(\"hello\") == HELLO:5 (got " + beta + ")");

        boolean[] p = AuxinAgent.probes("iso.app.Target");
        c.info("probes(iso.app.Target)=" + Checks.bits(p));
        c.check(p != null, "probe array exists for iso.app.Target");
        if (p != null) {
            c.check(p[idx("<init>", "()V")], "<init> probe SET");
            c.check(p[idx("alpha", "(I)I")], "alpha probe SET");
            c.check(p[idx("beta", "(Ljava/lang/String;)Ljava/lang/String;")], "beta probe SET");
            c.check(p[idx("touch", "()V")], "private touch() probe SET (called from <init>)");
            c.check(!p[idx("never", "(I)I")], "never() probe NOT set (never called)");
        }
        c.check(AuxinAgent.probes("iso.sealed.Target") == null,
                "the control module's class was never instrumented (outside ax.include.packages)");

        c.info("bridge=" + AuxinAgent.bridgeStatus()
                + " classesInstrumented=" + Health.classesInstrumented()
                + " skipped=" + Health.skipped());
        // FORWARD-LOOKING (VALIDATION C34). What makes the condy path legal from a named module
        // today is that the VM adds read edges to the BOOT and APPLICATION class loaders' unnamed
        // modules -- and the agent runtime happens to live in the latter. C34 wants the runtime
        // moved into an isolated AuxinClassLoader. Nothing grants a read edge to THAT.
        java.net.URLClassLoader isolatedAgentLoader = new java.net.URLClassLoader(
                new java.net.URL[]{new java.io.File(System.getProperty("iso.agentJar")).toURI().toURL()},
                null);
        c.check(!m.canRead(isolatedAgentLoader.getUnnamedModule()),
                "a named module does NOT read the unnamed module of a hypothetical isolated agent "
                        + "loader, even after being transformed: moving the runtime off the "
                        + "application class path (C34) would reintroduce the JPMS IllegalAccessError "
                        + "unless bridge delivery is used for named modules");
        isolatedAgentLoader.close();

        c.check(AuxinAgent.bridgeInstalled(),
                "java.lang.$Auxin installed under JPMS (status=" + AuxinAgent.bridgeStatus() + ")");
        c.check(Health.transformFailures() == 0, "zero transform failures");
        c.check(!Health.skipped().containsKey(Health.SKIP_AGENT_NOT_VISIBLE),
                "no class was skipped for agentNotVisible in a boot-layer module");
        c.report();
    }

    private static int idx(String name, String desc) {
        return AuxinAgent.probeIndex("iso.app.Target", name, desc);
    }
}
