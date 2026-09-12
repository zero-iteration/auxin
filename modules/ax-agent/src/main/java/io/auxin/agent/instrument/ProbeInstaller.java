package io.auxin.agent.instrument;

import io.auxin.agent.config.Options;
import io.auxin.agent.health.Health;
import io.auxin.agent.manifest.Manifest;
import io.auxin.agent.runtime.BootstrapBridge;
import io.auxin.agent.runtime.ProbeHolder;
import io.auxin.agent.util.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Tier-1: installs probes at INITIAL CLASS LOAD. Registered with the 1-argument
 * {@code addTransformer(t)} — i.e. <b>canRetransform = false</b>.
 *
 * <p>That single fact buys two things, both measured in E1:
 * <ol>
 *   <li>The JVM runs retransformation-INCAPABLE transformers first, so we are ahead of the
 *       OpenTelemetry agent (which is capable) regardless of {@code -javaagent} order.</li>
 *   <li>On any later retransform — OTel's startup batch, an APM's, ours — the JVM replays our
 *       cached output verbatim as the input to capable transformers. <b>Our probes survive
 *       other agents' retransforms.</b></li>
 * </ol>
 *
 * <p>Therefore: {@code classBeingRedefined != null} MUST return null. Acting on a redefinition
 * here would break both properties.
 */
public final class ProbeInstaller implements ClassFileTransformer {

    private final Options options;
    private final Manifest manifest;
    private final StartupBudget budget;
    private final ProbeEmitter emitter;

    public ProbeInstaller(Options options, Manifest manifest, StartupBudget budget) {
        this.options = options;
        this.manifest = manifest;
        this.budget = budget;
        this.emitter = new ProbeEmitter(options);
    }

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // FAIL OPEN, ABSOLUTELY. The JVM silently ignores a transformer that throws, so an
        // uncaught Throwable here would look exactly like "this code is dead" downstream.
        try {
            return doTransform(loader, internalName, classBeingRedefined, classfileBuffer);
        } catch (Throwable t) {
            Health.transformFailure();
            Health.skip(Health.SKIP_TRANSFORM_FAILURE);
            Log.debug("transform failed for " + internalName, t);
            return null;
        }
    }

    private byte[] doTransform(ClassLoader loader, String internalName,
                               Class<?> classBeingRedefined, byte[] buffer) {
        // ---- pass 1: name only. No parsing. This is the startup-CPU lever (A11/C13). ----
        if (classBeingRedefined != null) return null;   // HARD RULE: never act on a retransform
        if (internalName == null || buffer == null) return null;
        if (!options.enabled) return null;
        if (!options.tier1Enabled && !options.tier2Enabled) return null;

        // G5-BUG-1. The ignore rules used to be one flat list consulted HERE, ahead of the
        // scope, returning null without touching a counter. An application package that shared
        // a prefix with any entry was swallowed whole and the wire said
        // `classesInstrumented: 0, classesSkipped: {}, transformFailures: 0` -- the exact
        // "all of your code is dead" signature C37 exists to prevent. Precedence is now:
        // hard veto > explicit scope > soft veto, and every rejection of an in-scope class is
        // counted and named.
        final String hardVeto = IgnoreRules.hardVeto(internalName);
        if (hardVeto != null) {
            // The JDK, on the common startup path: not in scope, not counted, and touching a
            // counter for every java/* load would drown the signal. But if the operator's own
            // include list asked for this class, refusing is an event they must see.
            //
            // Our OWN runtime is the exception: resolving Health or ProbeHolder from inside the
            // transform of an agent class would be a recursive class load, and "we declined to
            // instrument ourselves" is not news. An include scope that overlaps it is reported
            // once at premain instead.
            if (!IgnoreRules.isOwnRuntime(hardVeto) && options.scope.included(internalName)) {
                ProbeHolder.observeLoad(internalName);
                vetoed(internalName, hardVeto, "never-instrumentable");
            }
            return null;
        }
        if (!options.scope.included(internalName)) return null;          // DEFAULT DENY

        // The ONE thing recorded ahead of "loaded", and only because CONTRACTS section 1 says
        // runtime-generated classes are never INVENTORIED either: they cannot appear in the
        // build manifest, so they can never be the subject of a dead-code verdict and their
        // absence from classesLoaded cannot mislabel anything. Admitting them is also the only
        // realistic way to reach the cap below — jacoco#655 is 2GB of exactly these names.
        if (IgnoreRules.generated(internalName)) {
            Health.skip("generatedClass");
            return null;
        }

        // C10 / G5-FINDING-4: "loaded" is its own fact, recorded before every skip below.
        ProbeHolder.observeLoad(internalName);

        if (options.scope.excluded(internalName)) {
            Health.skip(Health.SKIP_PACKAGE_KILL_SWITCH);
            return null;
        }
        final String foreignVeto = IgnoreRules.foreignVeto(internalName);
        if (foreignVeto != null) {
            // Another agent's runtime. A broad scope (`include.packages=com`) must not drag it
            // in; an operator who named something at least as specific meant it.
            final String matched = options.scope.matchedPrefix(internalName);
            if (!IgnoreRules.scopeWins(matched, foreignVeto)) {
                vetoed(internalName, foreignVeto, "another agent's runtime");
                return null;
            }
            if (Health.warnOnce("scopeOverridesIgnore:" + foreignVeto)) {
                Log.warn("ax.include.packages entry '" + matched + "' is more specific than the "
                        + "ignore-list entry '" + foreignVeto + "', so the scope wins and "
                        + internalName + " WILL be instrumented. Instrumenting another agent's "
                        + "runtime classes can cause LinkageError; narrow ax.include.packages "
                        + "if this was not intended.");
            }
        }
        if (budget.breached()) {
            Health.skip(Health.SKIP_BUDGET_EXCEEDED);
            return null;
        }
        Manifest.ClassEntry entry = manifest.byInternalName(internalName);
        if (entry == null) {
            Health.skip(Health.SKIP_NO_MANIFEST_ENTRY);
            return null;
        }
        // Still pass 1, still no parsing: one cached question per class loader. A loader that
        // cannot resolve ProbeHolder (OSGi, JBoss Modules, JPMS, some fat jars) needs the
        // java.lang bridge; without it, skipping is the only fail-open answer, because the
        // alternative is a NoClassDefFoundError raised inside a business method.
        final boolean agentVisible = LoaderVisibility.agentVisibleFrom(loader);
        if (!agentVisible) {
            if (!BootstrapBridge.installed()) {
                Health.skip(Health.SKIP_AGENT_NOT_VISIBLE);
                return null;
            }
            // F3: the bridge exists but a third party has taken its contents. Emitting the
            // prologue now would raise ExceptionInInitializerError in this class's <clinit>.
            if (!BootstrapBridge.intact()) {
                Health.skip(Health.SKIP_BRIDGE_TAMPERED);
                return null;
            }
        }

        // ---- pass 2: parse and rewrite, under the CPU budget ----
        long wallStart = System.nanoTime();
        long token = budget.start();
        try {
            ClassNode cn = new ClassNode();
            // flags 0: frames are read as FrameNodes and written straight back out. SKIP_FRAMES
            // would drop them and ClassWriter(0) would emit none -> VerifyError on v50+.
            //
            // EXPAND_FRAMES only when this class carries a tier-2 method: tier-2 adds a local,
            // and every existing frame has to learn about it (a frame that declares fewer
            // locals makes the verifier treat the slot as TOP at that point, which then fails
            // to merge with the handler frame). Expanding costs transform CPU, so it is paid
            // only by the 50-200 boundary methods, never by tier-1-only classes.
            boolean expand = options.tier2Enabled && entry.hasTier2;
            new ClassReader(buffer).accept(cn, expand ? ClassReader.EXPAND_FRAMES : 0);

            ProbeEmitter.Result r = emitter.instrument(cn, entry, agentVisible);
            if (r.skipReason != null) {
                Health.skip(r.skipReason);
                return null;
            }
            if (!r.changed()) return null;

            ClassWriter cw = new ClassWriter(0);   // NEVER COMPUTE_FRAMES (it loads classes)
            cn.accept(cw);
            byte[] out = cw.toByteArray();

            ProbeHolder.registerInstrumented(entry.name, entry.probeCount);
            Health.classInstrumented(r.probes, r.tier2Methods);
            if (Log.debugEnabled()) {
                Log.debug("instrumented " + entry.name + " probes=" + r.probes
                        + " tier2=" + r.tier2Methods + " condy=" + r.usedCondy
                        + " access=" + r.access + " loaderSeesAgent=" + agentVisible);
            }
            dump(internalName, out);
            return out;
        } finally {
            budget.end(token, wallStart);
        }
    }

    /**
     * An in-scope class we refuse to instrument (G5-BUG-1). Loud by construction: two counters
     * plus exactly one WARN per JVM naming the class and the prefix responsible.
     *
     * <p>{@code ignoredPrefix} carries the volume, {@code inScopeVetoed} is the one an alert can
     * be built on — an operator asked for this class by name and did not get it. Counting
     * without naming would leave them guessing which prefix did it, and naming every class would
     * make the agent chatter, so: all of them counted, the first one named.
     */
    private void vetoed(String internalName, String prefix, String why) {
        Health.skip(Health.SKIP_IGNORED_PREFIX);
        Health.skip(Health.SKIP_IN_SCOPE_VETOED);
        if (Health.warnOnce("inScopeVeto:" + prefix)) {
            Log.warn(internalName + " matches ax.include.packages but was NOT instrumented: the "
                    + "prefix '" + prefix + "' (" + why + ") vetoes it. Coverage for this class "
                    + "will be absent, which is NOT evidence that it is dead. Counted as "
                    + "ax_classes_skipped_total{reason=" + Health.SKIP_IN_SCOPE_VETOED + "}.");
        } else {
            Log.debug("in-scope class vetoed by '" + prefix + "': " + internalName);
        }
    }

    private void dump(String internalName, byte[] bytes) {
        if (options.dumpDir == null || options.dumpDir.isEmpty()) return;
        try {
            File f = new File(options.dumpDir, internalName.replace('/', '.') + ".class");
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            Log.debug("class dump failed", t);
        }
    }
}
