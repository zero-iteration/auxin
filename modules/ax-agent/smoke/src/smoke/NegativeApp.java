package smoke;

import io.auxin.agent.AuxinAgent;

/**
 * Fail-open probe. Runs the same target methods under broken/absent configuration and reports
 * what the agent did. The application must always produce the right answers.
 */
public class NegativeApp {

    public static void main(String[] args) {
        SmokeTarget t = new SmokeTarget();
        if (args.length > 0 && "flood".equals(args[0])) {
            // far more tier-2 events than a small ring can hold between drains:
            // the contract is drop-and-count, never block, never allocate, never throw
            for (int i = 0; i < 400000; i++) t.boundary(0);
        }
        if (args.length > 0 && "edgefail".equals(args[0])) {
            // Take the fail-open path deliberately, then keep using the application. The tier
            // must be off, counted, and NOT have dragged coverage or tier-2 down with it.
            EdgeTarget before = new EdgeTarget();
            System.out.println("EDGEFAIL_BEFORE=" + before.root(3));
            AuxinAgent.edgesForceFailOpen("negative-suite fail-open drill");
            System.out.println("EDGEFAIL_AFTER=" + before.root(3));
        }
        if (args.length > 0 && "edgeflood".equals(args[0])) {
            // the same contract for the edge ring, which is a second Ring and therefore has its
            // own drop accounting: a burst of edges must never evict a tier-2 event, and must
            // never block the application thread that produced it
            EdgeTarget fan = new EdgeTarget();
            for (int i = 0; i < 200; i++) fan.fanRoot(200);
        }
        boolean ok = t.alpha(10) == -5
                && t.gamma(2.0d) == 10.0d
                && "many:5".equals(t.beta("hello"))
                && t.boundary(3) == 179
                && t.answer() == 42
                && t.spin(10) == -2
                && new LegacyTarget().compute(5) == 20
                // Two packages that collide with the ignore list (G5-BUG-1): whether they end up
                // instrumented or vetoed, they must always still WORK.
                && new io.auxin.userapp.Thing().work(4) == 13
                && new com.datadog.trace.CustomerCode().handle(4) == 7
                // SCOPE-v3: the call-edge tier changes nothing the application can observe,
                // whether it is on, off, misconfigured, or has failed itself open.
                && new EdgeTarget().root(3) == 18
                && new EdgeTarget().outerRoot(3) == 19
                && edgeThrowStillThrows()
                && AuxinAgent.edgeActiveTraces() == 0
                && isolatedStillWorks();
        System.out.println("APP_OK=" + ok);
        System.out.println("ACTIVE=" + AuxinAgent.active());
        System.out.println("BRIDGE=" + AuxinAgent.bridgeStatus());
        System.out.println("INSTRUMENTED=" + (AuxinAgent.probes("smoke.SmokeTarget") != null));
        System.out.println("EDGES=" + AuxinAgent.edgesEnabled());
        System.out.println("EDGETRACES=" + AuxinAgent.edgeActiveTraces());
        System.out.println("FLUSH=" + AuxinAgent.flushNow());
        Runtime.getRuntime().halt(ok ? 0 : 1);
    }

    /**
     * An exception leaving a boundary method must still leave it, unchanged, through the edge
     * tier's own handler — and must leave no trace open behind it.
     */
    private static boolean edgeThrowStillThrows() {
        try {
            new EdgeTarget().throwingRoot(7);
            return false;
        } catch (IllegalStateException expected) {
            return "edge-7".equals(expected.getMessage());
        } catch (Throwable wrong) {
            System.out.println("EDGE_WRONG_THROWABLE=" + wrong);
            return false;
        }
    }

    /**
     * A class loaded by a loader that cannot see the agent jar. Whatever the agent decides to do
     * about it — bridge it, skip it, or be switched off entirely — the class must still work.
     */
    private static boolean isolatedStillWorks() {
        java.net.URLClassLoader isolated = null;
        try {
            java.net.URL here = NegativeApp.class.getProtectionDomain().getCodeSource().getLocation();
            isolated = new java.net.URLClassLoader(new java.net.URL[]{here}, null);
            Class<?> c = isolated.loadClass("smoke.BridgeTarget");
            Object o = c.getDeclaredConstructor().newInstance();
            Object r = c.getMethod("work", int.class).invoke(o, Integer.valueOf(4));
            return ((Integer) r).intValue() == 6;
        } catch (Throwable t) {
            System.out.println("ISOLATED_FAILED=" + t);
            t.printStackTrace(System.out);
            return false;
        } finally {
            try {
                if (isolated != null) isolated.close();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }
}
