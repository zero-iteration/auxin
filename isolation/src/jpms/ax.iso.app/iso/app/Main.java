package iso.app;

import java.lang.reflect.Field;

/**
 * Entry point for the true modular launch: {@code java --module-path mods -m ax.iso.app/iso.app.Main}.
 *
 * <p>The module cannot see the agent, so it reads its own probe array back through
 * {@code java.lang.$Auxin} -- java.lang only, which is exactly the channel A10 claims
 * survives every loader arrangement. Using it here is not circular: the probes themselves are
 * delivered to a boot-layer module by condy + ProbeHolder, a completely different path.
 */
public final class Main {

    /** manifest idx order is sorted (name, desc): <init>=0, alpha=1, beta=2, never=3, touch=4. */
    private static final boolean[] EXPECTED = {true, true, true, false, true};

    private static int checks;
    private static int bad;

    public static void main(String[] args) {
        Module m = Main.class.getModule();
        String pkg = Main.class.getPackage().getName();
        System.out.println("  info  module=" + m.getName() + " named=" + m.isNamed()
                + " loader=" + Main.class.getClassLoader()
                + " exports=" + m.isExported(pkg) + " opens=" + m.isOpen(pkg)
                + " readsUnnamed=" + m.canRead(ClassLoader.getSystemClassLoader().getUnnamedModule()));

        check(m.isNamed(), "running inside a NAMED module (" + m.getName() + ")");

        Target t = new Target();
        int a = t.alpha(10);
        String b = t.beta("hello");
        check(a == 47, "Target.alpha(10) == 47 (got " + a + ")");
        check("HELLO:5".equals(b), "Target.beta(\"hello\") == HELLO:5 (got " + b + ")");

        boolean[] p = probesViaBridge(Target.class.getName(), EXPECTED.length);
        check(p != null, "probe array reachable through java.lang.$Auxin from a named module");
        if (p != null) {
            check(p.length == EXPECTED.length, "probe array has " + EXPECTED.length + " slots (got " + p.length + ")");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < p.length; i++) sb.append(p[i] ? '1' : '0');
            System.out.println("  info  probes=" + sb);
            boolean match = p.length == EXPECTED.length;
            for (int i = 0; match && i < p.length; i++) match = p[i] == EXPECTED[i];
            check(match, "probes fired exactly for the methods that ran (<init>,alpha,beta,touch; NOT never)");
        }
        System.out.println("MODCHECK " + (checks - bad) + "/" + checks + (bad == 0 ? " OK" : " FAILED"));
        System.out.flush();
        Runtime.getRuntime().halt(bad == 0 ? 0 : 1);
    }

    /**
     * {@code ((Object) java.lang.$Auxin.data).equals(new Object[]{name, count})} replaces
     * args[0] with the probe array. Only java.lang types are named, so a named module needs no
     * read edge, no export and no open to do this.
     */
    private static boolean[] probesViaBridge(String className, int probeCount) {
        try {
            Class<?> bridge = Class.forName("java.lang.$Auxin");
            Field data = bridge.getField("data");
            Object holder = data.get(null);
            if (holder == null) return null;
            Object[] args = new Object[]{className, Integer.valueOf(probeCount)};
            holder.equals(args);
            return args[0] instanceof boolean[] ? (boolean[]) args[0] : null;
        } catch (Throwable t) {
            System.out.println("  info  bridge read failed: " + t);
            return null;
        }
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (ok) {
            System.out.println("  PASS  " + what);
        } else {
            System.out.println("  FAIL  " + what);
            bad++;
        }
    }
}
