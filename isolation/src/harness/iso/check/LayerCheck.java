package iso.check;

import io.auxin.agent.AuxinAgent;
import io.auxin.agent.health.Health;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * TEST 1d -- a named module in a CUSTOM ModuleLayer whose class loader's parent is the bootstrap
 * loader. This is the JPMS shape that actually isolates: JBoss Modules, Liberty, and any
 * application that builds its own layer look like this.
 *
 * <p>Two independent gates have to be cleared and the distinction is the whole point:
 * <ul>
 *   <li><b>visibility</b> -- can the loader FIND io.auxin.agent.runtime.ProbeHolder? Here,
 *       no: the parent is the bootstrap loader and the agent jar is on the application class
 *       path. So the condy probe would be a NoClassDefFoundError inside application code.</li>
 *   <li><b>readability</b> -- may the module LINK to it? Irrelevant once it cannot be found.</li>
 * </ul>
 * java.lang.$Auxin clears both by construction: it is a member of java.base, which every
 * loader can find and every module reads implicitly.
 */
public final class LayerCheck {

    public static void main(String[] args) throws Exception {
        Checks c = new Checks("jpms-custom-layer");

        Path mods = Paths.get(System.getProperty("iso.mods"));
        ModuleFinder finder = ModuleFinder.of(mods);
        Configuration cf = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), Set.of("ax.iso.layer"));
        // parent class loader == null => the BOOTSTRAP loader. The application class path,
        // and therefore the agent jar, is unreachable from inside this layer.
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(cf, null);
        ClassLoader loader = layer.findLoader("ax.iso.layer");
        c.info("layer loader=" + loader + " parent=" + loader.getParent());
        c.check(loader.getParent() == null, "the layer's loader has the BOOTSTRAP loader as parent");

        boolean invisible;
        try {
            Class.forName("io.auxin.agent.runtime.ProbeHolder", false, loader);
            invisible = false;
        } catch (ClassNotFoundException expected) {
            invisible = true;
        }
        c.check(invisible, "the layer's loader genuinely cannot resolve ProbeHolder");

        Class<?> target = loader.loadClass("iso.layer.Target");
        Module m = target.getModule();
        c.info("module=" + m.getName() + " named=" + m.isNamed() + " layer==boot? "
                + (m.getLayer() == ModuleLayer.boot())
                + " readsUnnamed=" + m.canRead(ClassLoader.getSystemClassLoader().getUnnamedModule()));
        c.check(m.isNamed() && "ax.iso.layer".equals(m.getName()),
                "iso.layer.Target is in named module ax.iso.layer");
        c.check(m.getLayer() != ModuleLayer.boot(), "that module lives in a NON-BOOT layer");

        int alpha;
        String beta;
        try {
            Object t = target.getDeclaredConstructor().newInstance();
            alpha = ((Integer) target.getMethod("alpha", int.class).invoke(t, Integer.valueOf(10))).intValue();
            beta = (String) target.getMethod("beta", String.class).invoke(t, "hello");
        } catch (Throwable e) {
            c.failed("application code in the custom layer threw", e);
            c.report();
            return;
        }
        c.check(alpha == 47, "Target.alpha(10) == 47 in the custom layer (got " + alpha + ")");
        c.check("HELLO:5".equals(beta), "Target.beta(\"hello\") == HELLO:5 (got " + beta + ")");

        boolean[] p = AuxinAgent.probes("iso.layer.Target");
        c.info("probes(iso.layer.Target)=" + Checks.bits(p) + " skipped=" + Health.skipped());
        c.check(p != null, "probe array exists for the layer's class");
        if (p != null) {
            c.check(p[idx("<init>", "()V")], "<init> probe SET");
            c.check(p[idx("alpha", "(I)I")], "alpha probe SET");
            c.check(p[idx("beta", "(Ljava/lang/String;)Ljava/lang/String;")], "beta probe SET");
            c.check(!p[idx("never", "(I)I")], "never() probe NOT set");
        }
        c.check(Health.transformFailures() == 0, "zero transform failures");
        c.check(!Health.skipped().containsKey(Health.SKIP_AGENT_NOT_VISIBLE),
                "nothing was skipped for agentNotVisible: the bridge carried it");
        c.report();
    }

    private static int idx(String name, String desc) {
        return AuxinAgent.probeIndex("iso.layer.Target", name, desc);
    }
}
