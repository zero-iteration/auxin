package io.auxin.agent.config;

import io.auxin.agent.util.Log;

/**
 * C50 — THE TEST-LIVENESS TRAP.
 *
 * <p>Google Sensenmann: <i>"we cannot use test runs as a 'liveness' signal ... We would only be
 * able to clean up untested code, which would severely hamper our efforts."</i>
 *
 * <p>If this agent runs in CI, a test run, staging or a canary smoke test, every tested method
 * looks permanently alive and the product inverts: it could then only ever propose deleting
 * UNTESTED code. So:
 *
 * <ul>
 *   <li><b>Fail closed.</b> Unset or unrecognised {@code ax.environment} =&gt; every window is
 *       stamped {@code livenessEvidence: false}. Absent classification is never production.</li>
 *   <li>We still instrument and still report — the window is simply not liveness evidence, and
 *       says so on the wire so the collector can discard it.</li>
 *   <li>A detected test runner is reported honestly ({@code testRunnerDetected}), never
 *       silently swallowed and never a reason to refuse to run.</li>
 * </ul>
 */
public final class EnvironmentClassification {

    private static final String[] TEST_RUNNER_MARKERS = {
            "org.junit.",
            "junit.framework.",
            "org.junit.platform.",
            "org.testng.",
            "org.apache.maven.surefire.",
            "org.apache.maven.plugin.surefire.",
            "org.apache.maven.plugin.failsafe.",
            "org.apache.maven.surefire.booter.",
            "org.gradle.api.internal.tasks.testing.",
            "worker.org.gradle.process.internal.worker.GradleWorkerMain",
            "org.gradle.process.internal.worker.GradleWorkerMain",
            "org.apache.maven.cli.MavenCli",
            "org.codehaus.plexus.classworlds.launcher.Launcher",
            "org.scalatest.tools.Runner",
            "io.cucumber.core.cli.Main",
            "org.spockframework."
    };

    private static final String[] TEST_CLASSPATH_MARKERS = {
            "surefire", "failsafe", "junit", "testng",
            "gradle-worker.jar", "/test-classes", "\\test-classes"
    };

    private final boolean livenessEvidence;
    private final boolean testRunnerDetected;
    private final String environment;
    private final String detectedVia;

    private EnvironmentClassification(boolean live, boolean test, String env, String via) {
        this.livenessEvidence = live;
        this.testRunnerDetected = test;
        this.environment = env;
        this.detectedVia = via;
    }

    public static EnvironmentClassification detect(Options options) {
        boolean testRunner = false;
        String via = "";
        try {
            for (StackTraceElement[] stack : Thread.getAllStackTraces().values()) {
                for (int i = 0; i < stack.length && !testRunner; i++) {
                    String cn = stack[i].getClassName();
                    for (int m = 0; m < TEST_RUNNER_MARKERS.length; m++) {
                        if (cn.startsWith(TEST_RUNNER_MARKERS[m])) {
                            testRunner = true;
                            via = "stack:" + TEST_RUNNER_MARKERS[m];
                            break;
                        }
                    }
                }
                if (testRunner) break;
            }
        } catch (Throwable t) {
            Log.debug("test-runner stack probe failed", t);
        }
        if (!testRunner) {
            try {
                String cp = System.getProperty("java.class.path", "")
                        + " " + System.getProperty("sun.java.command", "");
                String lower = cp.toLowerCase();
                for (int m = 0; m < TEST_CLASSPATH_MARKERS.length; m++) {
                    if (lower.contains(TEST_CLASSPATH_MARKERS[m])) {
                        testRunner = true;
                        via = "classpath:" + TEST_CLASSPATH_MARKERS[m];
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.debug("test-runner classpath probe failed", t);
            }
        }

        boolean production = options.productionClassified();
        boolean liveness = production && !testRunner;

        if (!production) {
            Log.warn("ax.environment=" + (options.environment.isEmpty() ? "<unset>" : options.environment)
                    + " is not in the production allowlist " + options.productionEnvironments
                    + " -> livenessEvidence=false on EVERY window (C50 fail-closed). "
                    + "Coverage from this JVM must never be used as evidence that code is alive or dead.");
        }
        if (testRunner) {
            Log.warn("test runner detected (" + via + ") -> testRunnerDetected=true, livenessEvidence=false. "
                    + "Test execution is not a liveness signal (C50).");
        }
        return new EnvironmentClassification(liveness, testRunner,
                options.environment.isEmpty() ? "unclassified" : options.environment, via);
    }

    public boolean livenessEvidence() { return livenessEvidence; }
    public boolean testRunnerDetected() { return testRunnerDetected; }
    public String environment() { return environment; }
    public String detectedVia() { return detectedVia; }
}
