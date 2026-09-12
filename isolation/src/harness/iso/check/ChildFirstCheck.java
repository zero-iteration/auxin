package iso.check;

import io.auxin.agent.AuxinAgent;
import io.auxin.agent.health.Health;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TEST 3 -- child-first / parent-last loaders, and the Spring Boot fat-jar double definition.
 *
 * <p>Driven by {@code -Diso.mode}:
 * <ul>
 *   <li>{@code condy} -- the child-first loader carries only the application classes, so
 *       io.auxin still resolves through its parent. LoaderVisibility must say VISIBLE and
 *       the ordinary condy probe must be used.</li>
 *   <li>{@code bridge} -- the child-first loader ALSO carries the agent jar, so it defines its
 *       OWN io.auxin.agent.runtime.ProbeHolder. Class.forName succeeds but returns a
 *       DIFFERENT class: LoaderVisibility's identity comparison must catch that and fall back to
 *       java.lang.$Auxin. Getting this wrong means probes vanish into a second, private
 *       ProbeHolder that nothing ever flushes.</li>
 *   <li>{@code skip} -- same loader as {@code bridge} but the bridge is unavailable (disabled,
 *       or already owned by another agent). The class must be skipped and counted, never broken.</li>
 * </ul>
 *
 * <p>In every mode iso.child.Widget is ALSO loaded by the application class loader, so two
 * distinct runtime classes share one binary name -- the fat-jar double definition A10 lists.
 */
public final class ChildFirstCheck {

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("iso.mode", "condy");
        Checks c = new Checks("child-first-" + mode);

        // ---- copy 1: the application class loader ----
        iso.child.Widget appCopy = new iso.child.Widget();
        c.check(appCopy.alpha(10) == 47, "app-loader Widget.alpha(10) == 47");
        c.check(iso.child.Widget.class.getClassLoader() == ClassLoader.getSystemClassLoader(),
                "the first copy really is defined by the application class loader");

        // ---- copy 2: a child-first loader ----
        List<URL> urls = new ArrayList<URL>();
        urls.add(new File(System.getProperty("iso.appClasses")).toURI().toURL());
        boolean shadowAgent = !"condy".equals(mode);
        if (shadowAgent) urls.add(new File(System.getProperty("iso.agentJar")).toURI().toURL());

        ChildFirst child = new ChildFirst(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader());

        Class<?> holderViaChild = Class.forName("io.auxin.agent.runtime.ProbeHolder", false, child);
        c.info("ProbeHolder seen by the child-first loader = " + holderViaChild.getClassLoader());
        if (shadowAgent) {
            c.check(holderViaChild != io.auxin.agent.runtime.ProbeHolder.class,
                    "the child-first loader SHADOWS ProbeHolder with its own copy "
                            + "(Class.forName succeeds but the class is not the agent's)");
        } else {
            c.check(holderViaChild == io.auxin.agent.runtime.ProbeHolder.class,
                    "without shadowing the child-first loader resolves the agent's own ProbeHolder");
        }

        Class<?> childWidget = child.loadClass("iso.child.Widget");
        c.check(childWidget.getClassLoader() == child,
                "the second copy is defined by the child-first loader");
        c.check(childWidget != iso.child.Widget.class,
                "two DISTINCT runtime classes now share the binary name iso.child.Widget "
                        + "(the Spring Boot fat-jar double definition)");

        if ("tamper".equals(mode)) {
            tamper(c, childWidget, appCopy);
            return;
        }
        if ("tamper-late".equals(mode)) {
            tamperLate(c, childWidget, appCopy);
            return;
        }

        Object w2;
        int alpha2;
        int childOnly;
        try {
            w2 = childWidget.getDeclaredConstructor().newInstance();
            alpha2 = ((Integer) childWidget.getMethod("alpha", int.class)
                    .invoke(w2, Integer.valueOf(10))).intValue();
            childOnly = ((Integer) childWidget.getMethod("childOnly", int.class)
                    .invoke(w2, Integer.valueOf(4))).intValue();
        } catch (Throwable e) {
            c.failed("application code in the child-first loader threw", e);
            c.report();
            return;
        }
        c.check(alpha2 == 47, "child-first Widget.alpha(10) == 47 (got " + alpha2 + ")");
        c.check(childOnly == 11, "child-first Widget.childOnly(4) == 11 (got " + childOnly + ")");

        boolean[] p = AuxinAgent.probes("iso.child.Widget");
        c.info("probes(iso.child.Widget)=" + Checks.bits(p)
                + " bridge=" + AuxinAgent.bridgeStatus()
                + " skipped=" + Health.skipped());

        if ("skip".equals(mode)) {
            Map<String, Long> skipped = Health.skipped();
            c.check(!AuxinAgent.bridgeInstalled(),
                    "the bridge is NOT installed in this JVM (status=" + AuxinAgent.bridgeStatus() + ")");
            c.check(skipped.containsKey(Health.SKIP_AGENT_NOT_VISIBLE)
                            && skipped.get(Health.SKIP_AGENT_NOT_VISIBLE).longValue() > 0,
                    "the child-first class was SKIPPED and counted as agentNotVisible");
            c.check(Health.transformFailures() == 0,
                    "the skip was a decision, not a transform failure (0 transform failures)");
            c.check(p != null && p[idx("alpha")],
                    "the app-loader copy of Widget IS still instrumented (alpha probe set)");
            c.check(p != null && !p[idx("childOnly")],
                    "childOnly ran only in the SKIPPED copy and recorded nothing: "
                            + "a coverage hole, which is the designed fail-open, not an outage");
        } else {
            c.check(p != null, "probe array exists for iso.child.Widget");
            if (p != null) {
                c.check(p[idx("alpha")], "alpha probe SET");
                c.check(p[idx("childOnly")],
                        "childOnly probe SET -- it ran ONLY in the child-first copy, so this bit "
                                + "proves the second copy's probes reached the AGENT's ProbeHolder");
                c.check(!p[idx("never")], "never() probe NOT set");
            }
            c.check(Health.transformFailures() == 0, "zero transform failures");
        }

        // Whatever happened above, application code must be unharmed.
        c.check(appCopy.beta("hello").equals("HELLO:5"), "app-loader copy still correct afterwards");
        c.check(((String) childWidget.getMethod("beta", String.class).invoke(w2, "hello")).equals("HELLO:5"),
                "child-first copy still correct afterwards");
        c.report();
    }

    /**
     * ADVERSARIAL: a third party has overwritten java.lang.$Auxin.data (see RivalAgent).
     * The bridge prologue ends in {@code CHECKCAST [Z} on args[0], so a handler that does not
     * fill args[0] in turns the very first use of the class into an ExceptionInInitializerError
     * -- raised inside application code, which is the failure mode the whole design exists to
     * avoid. This asserts the CURRENT behaviour so that any future hardening shows up as a
     * deliberate change. It is a documented HAZARD, not a passing grade.
     */
    private static void tamper(Checks c, Class<?> childWidget, iso.child.Widget appCopy) {
        c.check(AuxinAgent.bridgeInstalled(),
                "ax-agent installed the bridge before the tamper (status="
                        + AuxinAgent.bridgeStatus() + ")");
        Throwable thrown = null;
        try {
            childWidget.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            thrown = Checks.root(e);
        }
        c.info("first use of the bridge-instrumented class threw: " + thrown);
        // F3 MITIGATED (not closed). ax-agent now remembers the exact Data instance and checks
        // BootstrapBridge.intact() before taking the bridge path, degrading to bridgeTampered
        // instead of throwing. That catches the realistic case -- a second agent's premain, which
        // runs before any application class loads, which is exactly this scenario.
        //
        // F2 CLOSES THE RESIDUAL RACE that this check could not. The bridge's condy bootstrap
        // method is now one ax-agent authors itself ($axInit on the instrumented class), and it
        // ends in INSTANCEOF [Z: a tamper landing AFTER intact() returned true now degrades to a
        // throwaway boolean[] instead of a ClassCastException in application code. This scenario
        // still exercises the OUTER defence, because the rival's premain runs before any
        // application class loads, so the class is skipped as bridgeTampered and never reaches
        // the inner one. If this assertion ever fails again, F3 has regressed.
        c.check(thrown == null,
                "F3 MITIGATED: tampering with java.lang.$Auxin.data degrades to "
                        + "bridgeTampered instead of breaking application code (threw: "
                        + thrown + ")");
        c.check(appCopy.alpha(10) == 47,
                "classes NOT routed through the bridge (condy path) are unaffected by the tamper");
        c.report();
    }

    /**
     * ADVERSARIAL, and the exact half {@code BootstrapBridge.intact()} structurally CANNOT catch:
     * the hook is taken <b>after</b> the class was transformed, in the window between the agent's
     * check and the first execution of one of its probes. No amount of checking at transform time
     * closes that window -- a public static field is writable at any instant.
     *
     * <p>Before F2 this was the unfixable residue of the F3 hazard: the {@code <clinit>} prologue
     * ended in {@code AALOAD; CHECKCAST [Z}, so the first use of the class raised
     * {@code ExceptionInInitializerError} <b>inside application code</b>. F2's bootstrap method is
     * one ax-agent authors itself, and it ends in {@code INSTANCEOF [Z}: the tampered value is
     * discarded and a throwaway {@code boolean[]} returned instead. The class runs correctly and
     * records nothing -- a coverage hole, which is the designed fail-open.
     *
     * <p>The class is loaded but NOT initialised before the tamper, which is what puts the
     * condy resolution (and therefore {@code $axInit}) on the far side of it.
     */
    private static void tamperLate(Checks c, Class<?> childWidget, iso.child.Widget appCopy)
            throws Exception {
        c.check(AuxinAgent.bridgeInstalled(),
                "ax-agent installed the bridge (status=" + AuxinAgent.bridgeStatus() + ")");
        boolean[] before = AuxinAgent.probes("iso.child.Widget");
        int childOnlyIdx = idx("childOnly");
        c.check(before != null && childOnlyIdx >= 0,
                "the child-first copy was instrumented and childOnly has a manifest index");
        c.check(before != null && !before[childOnlyIdx],
                "childOnly has not run yet, so its bit is still the thing under test");

        java.lang.reflect.Field data = Class.forName("java.lang.$Auxin").getField("data");
        Object was = data.get(null);
        // A plain Object: its equals() is identity, so it never fills args[0] in. Exactly the
        // shape RivalAgent uses, but landing after the transform instead of before it.
        data.set(null, new Object());
        c.check(data.get(null) != was,
                "java.lang.$Auxin.data was overwritten AFTER the class was transformed, "
                        + "which is the window no transform-time check can close");

        Throwable thrown = null;
        int childOnly = -1;
        try {
            Object w = childWidget.getDeclaredConstructor().newInstance();
            childOnly = ((Integer) childWidget.getMethod("childOnly", int.class)
                    .invoke(w, Integer.valueOf(4))).intValue();
        } catch (Throwable e) {
            thrown = Checks.root(e);
        }
        boolean[] after = AuxinAgent.probes("iso.child.Widget");
        if ("field".equals(System.getProperty("iso.bridgeShape", "selfbsm"))) {
            // The differential that makes the F2 claim non-vacuous. Same JVM, same tamper, same
            // instant -- only the bridge shape differs, and the pre-F2 shape raises inside
            // application code because a <clinit> prologue cannot carry a guard branch.
            c.check(thrown instanceof ClassCastException,
                    "ROLLBACK HAZARD (ax.bridge.shape=field): the SAME late tamper DOES reach "
                            + "application code -- the <clinit> prologue's CHECKCAST [Z throws ("
                            + thrown + "). This is the residue F2 removes, asserted so the claim "
                            + "is measured against its own counterfactual.");
            c.check(childOnly == -1,
                    "ROLLBACK HAZARD: the application method never completed (got " + childOnly + ")");
        } else {
            c.check(thrown == null,
                    "F3 CLOSED BY F2: a tamper landing after the transform does NOT reach "
                            + "application code -- $axInit's INSTANCEOF [Z guard discards it "
                            + "(threw: " + thrown + ")");
            c.check(childOnly == 11,
                    "the bridge-instrumented class still computed the right answer (got "
                            + childOnly + ")");
            c.check(after != null && !after[childOnlyIdx],
                    "childOnly ran and recorded NOTHING: its probe went into the throwaway array. "
                            + "A coverage hole, which is the designed fail-open, not an outage.");
        }
        c.check(Health.transformFailures() == 0,
                "the degradation was a decision, not a transform failure");
        c.check(appCopy.alpha(10) == 47,
                "classes NOT routed through the bridge (condy path) are unaffected by the tamper");
        c.report();
    }

    private static int idx(String method) {
        String desc = "alpha".equals(method) || "childOnly".equals(method) || "never".equals(method)
                ? "(I)I" : "()V";
        return AuxinAgent.probeIndex("iso.child.Widget", method, desc);
    }
}
