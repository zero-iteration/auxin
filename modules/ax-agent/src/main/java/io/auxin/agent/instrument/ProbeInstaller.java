package io.auxin.agent.instrument;

import io.auxin.agent.config.Options;
import io.auxin.agent.health.Health;
import io.auxin.agent.manifest.Manifest;
import io.auxin.agent.runtime.BootstrapBridge;
import io.auxin.agent.runtime.ProbeHolder;
import io.auxin.agent.util.Log;
import io.auxin.trace.runtime.TraceHealth;
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
 *
 * <h3>SCOPE-v3.1: ONE transformer, two scopes</h3>
 * The per-request trace tier used to be a second {@code -javaagent} with a second
 * {@code ClassFileTransformer}. It is now emitted from this one, and that is not tidiness: the
 * startup-cost research (A11/G4) measured a <b>20x</b> regression from N transformers each
 * walking the class hierarchy, so a third transformer was never an option. There is one
 * transformer pair in this JVM — this installer (incapable) and {@link ProbeStripper} (capable)
 * — and there will not be a third.
 *
 * <p>Consequences, all deliberate:
 * <ul>
 *   <li><b>Two independent scopes, one pass.</b> {@code ax.include.packages} admits a class to
 *       the coverage/tier-2/edge tiers; {@code ax.trace.include.packages} admits it to the trace
 *       tier. Either alone is enough to parse the class; neither implies the other. A class in
 *       both is parsed ONCE and written ONCE.</li>
 *   <li><b>One visibility question.</b> One jar means {@code ProbeHolder} and
 *       {@code TraceRuntime} are loaded by the same loader, so {@link LoaderVisibility} answers
 *       for both. The consequences still differ: tier-1 can reach an agent-invisible loader
 *       through {@code java.lang.$Auxin}, and the trace tier cannot (every trace probe is a
 *       per-invocation call, and an {@code equals}-based hop allocates). So such a class gets
 *       bridged probes and no trace frames, counted as {@code agentNotVisible}.</li>
 *   <li><b>One ignore list.</b> {@link IgnoreRules} hard-vetoes {@code io/auxin/trace/} too.</li>
 * </ul>
 */
public final class ProbeInstaller implements ClassFileTransformer {

    private final Options options;
    private final Manifest manifest;
    private final StartupBudget budget;
    private final ProbeEmitter emitter;

    /**
     * @param traceArmed did the trace tier arm at premain? See
     *                   {@link ProbeEmitter#ProbeEmitter(Options, boolean)} — it is not the same
     *                   question as {@code ax.trace.enabled}.
     */
    public ProbeInstaller(Options options, Manifest manifest, StartupBudget budget,
                          boolean traceArmed) {
        this.options = options;
        this.manifest = manifest;
        this.budget = budget;
        this.emitter = new ProbeEmitter(options, traceArmed);
    }

    /**
     * Resolves EVERY class on the transform path, before this transformer is registered.
     *
     * <p>Preserved verbatim in intent from the tracer's own premain, because it fixes a bug that
     * actually happened. A class reached for the first time from inside {@code transform()} is a
     * re-entrant load on the same thread, which the JVM answers with
     * {@code LinkageError: attempted duplicate class definition}. The trace smoke suite produced
     * exactly that on its first run, for the class holding the hard-veto list — the list that
     * would have prevented it — and it presented as <b>"premain failed, the tracer is disabled"
     * AFTER the transformer had already been registered</b>, i.e. a half-armed agent.
     *
     * <p>Naming the classes is not enough. This runs the <b>complete ASM round trip</b> — parse
     * a real class file into a {@link ClassNode}, walk every method through both emitters with
     * both scopes false so nothing is instrumented, and write it back out with a
     * {@link ClassWriter} — because that is what genuinely loads ASM's reader, writer, tree
     * nodes and every helper the emitters touch.
     *
     * <p>Called from {@code AuxinAgent.premain} BEFORE {@code addTransformer}. Failure is
     * survivable (the first transform is just slower), so it is caught and logged at debug.
     */
    public void warmUp(byte[] anyClassBytes) {
        try {
            IgnoreRules.hardVeto("java/lang/Object");
            IgnoreRules.foreignVeto("x/Y");
            IgnoreRules.generated("x/Y");
            IgnoreRules.agentRuntime();
            IgnoreRules.asmRuntime();
            IgnoreRules.traceRuntime();
            LoaderVisibility.agentVisibleFrom(ProbeInstaller.class.getClassLoader());
            io.auxin.trace.runtime.SiteRegistry.frameCount();
            TraceHealth.skips();
            Health.skipped();
            if (anyClassBytes == null) return;
            ClassNode cn = new ClassNode();
            new ClassReader(anyClassBytes).accept(cn, 0);
            // entry == null and both trace scopes false: every method is walked and none is
            // instrumented, which is all that is needed to resolve the path and everything on it.
            emitter.instrument(cn, null, true, false, false);
            // ...except the trace emitter, which `instrument` skips outright when neither trace
            // scope matches. Warmed explicitly, or it would first be resolved from inside
            // transform() -- the re-entrant load this whole method exists to prevent.
            emitter.warmTrace(cn);
            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            cw.toByteArray();
        } catch (Throwable t) {
            Log.debug("transform-path warm-up failed; the first transform may be slower", t);
        }
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
        // manifest == null means the coverage side never armed (no ax.include.packages, no
        // manifest, or an unreadable schema). Probe indices come from the manifest and are never
        // derived at runtime (C7), so with no manifest there is no agent tier at all -- exactly
        // the state the premain used to express by returning before addTransformer.
        final boolean anyAgentTier = manifest != null
                && (options.tier1Enabled || options.tier2Enabled || options.edgesEnabled);
        final boolean traceArmed = emitter.traceArmed();
        if (!anyAgentTier && !traceArmed) return null;

        // The two scopes, decided on the NAME alone so that a class in neither is rejected
        // without a parse -- the whole point of pass 1. Default-deny on both sides: an unset
        // ax.trace.include.packages traces nothing, exactly as an unset ax.include.packages
        // instruments nothing.
        final boolean inTrace = traceArmed && options.trace.scope.included(internalName);
        final boolean inEntry = traceArmed
                && (inTrace || options.trace.entryScope.included(internalName));

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
            if (!IgnoreRules.isOwnRuntime(hardVeto)) {
                if (options.scope.included(internalName)) {
                    ProbeHolder.observeLoad(internalName);
                    vetoed(internalName, hardVeto, "never-instrumentable");
                }
                if (inTrace || inEntry) traceVetoed(internalName, hardVeto, "never-instrumentable");
            }
            return null;
        }

        final boolean inAgentScope = anyAgentTier && options.scope.included(internalName);
        if (!inAgentScope && !inTrace && !inEntry) return null;          // DEFAULT DENY, both

        // The ONE thing recorded ahead of "loaded", and only because CONTRACTS section 1 says
        // runtime-generated classes are never INVENTORIED either: they cannot appear in the
        // build manifest, so they can never be the subject of a dead-code verdict and their
        // absence from classesLoaded cannot mislabel anything. Admitting them is also the only
        // realistic way to reach the cap below — jacoco#655 is 2GB of exactly these names.
        if (IgnoreRules.generated(internalName)) {
            if (inAgentScope) Health.skip("generatedClass");
            if (inTrace || inEntry) TraceHealth.skip("generatedClass");
            return null;
        }

        // C10 / G5-FINDING-4: "loaded" is its own fact, recorded before every skip below.
        // Gated on the COVERAGE scope: classesLoaded is a coverage-scope statement on the wire,
        // and a class admitted only by ax.trace.include.packages was never claimed by it.
        if (inAgentScope) ProbeHolder.observeLoad(internalName);

        // Level three of the kill switch, per scope: each tier obeys its own exclude list.
        boolean agentOn = inAgentScope;
        boolean traceOn = inTrace;
        boolean entryOn = inEntry;
        if (agentOn && options.scope.excluded(internalName)) {
            Health.skip(Health.SKIP_PACKAGE_KILL_SWITCH);
            agentOn = false;
        }
        if ((traceOn || entryOn) && options.trace.scope.excluded(internalName)) {
            TraceHealth.skip("packageKillSwitch");
            traceOn = false;
            entryOn = false;
        }
        if (!agentOn && !traceOn && !entryOn) return null;

        final String foreignVeto = IgnoreRules.foreignVeto(internalName);
        if (foreignVeto != null) {
            // Another agent's runtime. A broad scope (`include.packages=com`) must not drag it
            // in; an operator who named something at least as specific meant it.
            final String matched = options.scope.matchedPrefix(internalName);
            if (agentOn && !IgnoreRules.scopeWins(matched, foreignVeto)) {
                vetoed(internalName, foreignVeto, "another agent's runtime");
                agentOn = false;
            } else if (agentOn && Health.warnOnce("scopeOverridesIgnore:" + foreignVeto)) {
                Log.warn("ax.include.packages entry '" + matched + "' is more specific than the "
                        + "ignore-list entry '" + foreignVeto + "', so the scope wins and "
                        + internalName + " WILL be instrumented. Instrumenting another agent's "
                        + "runtime classes can cause LinkageError; narrow ax.include.packages "
                        + "if this was not intended.");
            }
            if (traceOn || entryOn) {
                final String tm = options.trace.scope.matchedPrefix(internalName);
                if (!IgnoreRules.scopeWins(tm, foreignVeto)) {
                    traceVetoed(internalName, foreignVeto, "another agent's runtime");
                    traceOn = false;
                    entryOn = false;
                }
            }
            if (!agentOn && !traceOn && !entryOn) return null;
        }

        // The startup CPU budget bounds the WHOLE transform, trace tier included: it exists to
        // cap what instrumentation costs a starting JVM, and a tier that could opt out of it
        // would make the cap a fiction.
        if (budget.breached()) {
            if (agentOn) Health.skip(Health.SKIP_BUDGET_EXCEEDED);
            if (traceOn || entryOn) TraceHealth.skip("budgetExceeded");
            return null;
        }

        Manifest.ClassEntry entry = null;
        if (agentOn) {
            entry = manifest.byInternalName(internalName);
            if (entry == null) Health.skip(Health.SKIP_NO_MANIFEST_ENTRY);
        }

        // Still pass 1, still no parsing: one cached question per class loader. A loader that
        // cannot resolve ProbeHolder (OSGi, JBoss Modules, JPMS, some fat jars) needs the
        // java.lang bridge; without it, skipping is the only fail-open answer, because the
        // alternative is a NoClassDefFoundError raised inside a business method.
        //
        // One jar since SCOPE-v3.1, so this is ONE question with TWO consequences: tier-1 has a
        // bridge and the trace tier structurally cannot have one (every trace probe is a
        // per-invocation call and an Object.equals hop allocates an Object[]), so an
        // agent-invisible loader keeps bridged probes and loses trace frames.
        final boolean agentVisible = LoaderVisibility.agentVisibleFrom(loader);
        if (!agentVisible) {
            if (traceOn || entryOn) {
                TraceHealth.skip("agentNotVisible");
                if (TraceHealth.warnOnce("agentNotVisible")) {
                    Log.warn("classes loaded by " + describe(loader) + " cannot resolve "
                            + "io.auxin.trace.runtime.TraceRuntime, so they are NOT traced (they "
                            + "are never broken). Counted as skipped.agentNotVisible. There is no "
                            + "java.lang bridge for a per-invocation call; see "
                            + "docs/TRADE-OFFS.md.");
                }
                traceOn = false;
                entryOn = false;
            }
            if (entry != null) {
                if (!BootstrapBridge.installed()) {
                    Health.skip(Health.SKIP_AGENT_NOT_VISIBLE);
                    entry = null;
                } else if (!BootstrapBridge.intact()) {
                    // F3: the bridge exists but a third party has taken its contents. Emitting
                    // the prologue now would raise ExceptionInInitializerError in <clinit>.
                    Health.skip(Health.SKIP_BRIDGE_TAMPERED);
                    entry = null;
                }
            }
        }
        if (entry == null && !traceOn && !entryOn) return null;

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
            //
            // The edge tier joins the same condition rather than widening it: its ROOTS are the
            // tier-2 boundary methods and they get an exception handler, whose frame has to be
            // written expanded when the rest of the method's frames are. Its CALLEES add no
            // local, no branch and no handler, so a class with no boundary method still costs
            // nothing extra to read -- which keeps the A11 startup-CPU bill exactly where it is.
            //
            // The TRACE tier does not widen it either: it adds no local, so it needs nothing
            // expanded. It reads the form off each method instead (TraceEmitter.hasExpandedFrame)
            // and writes F_NEW or F_FULL to match, because ASM cannot mix the two in one method.
            boolean expand = entry != null && entry.hasTier2
                    && (options.tier2Enabled || options.edgesEnabled);
            new ClassReader(buffer).accept(cn, expand ? ClassReader.EXPAND_FRAMES : 0);

            ProbeEmitter.Result r = emitter.instrument(cn, entry, agentVisible, traceOn, entryOn);
            if (r.skipReason != null) Health.skip(r.skipReason);
            if (!r.changed()) return null;

            ClassWriter cw = new ClassWriter(0);   // NEVER COMPUTE_FRAMES (it loads classes)
            cn.accept(cw);
            byte[] out = cw.toByteArray();

            // The installed-probe mask travels with the array it indexes: without it the drain
            // thread cannot tell a slot that is waiting for a call from a slot nothing will ever
            // write, and Tier-1b's strip gate needs exactly that distinction.
            if (entry != null && r.installedProbes != null) {
                ProbeHolder.registerInstrumented(entry.name, entry.probeCount, r.installedProbes);
            }
            if (entry != null && (r.probes > 0 || r.tier2Methods > 0 || r.edgeMethods > 0)) {
                Health.classInstrumented(r.probes, r.tier2Methods);
            }
            if (r.traceChanged) TraceHealth.CLASSES_INSTRUMENTED.incrementAndGet();
            if (Log.debugEnabled()) {
                Log.debug("instrumented " + internalName + " probes=" + r.probes
                        + " tier2=" + r.tier2Methods + " edges=" + r.edgeMethods
                        + " edgeRoots=" + r.edgeRoots + " condy=" + r.usedCondy
                        + " access=" + r.access + " loaderSeesAgent=" + agentVisible
                        + " traceMethods=" + r.traceMethods
                        + " traceEntries=" + r.traceEntries + " traceArms=" + r.traceArms
                        + " traceWrappedCallSites=" + r.traceWrappedCallSites
                        + " bytes " + buffer.length + " -> " + out.length);
            }
            dump(internalName, out);
            return out;
        } finally {
            budget.end(token, wallStart);
        }
    }

    private static String describe(ClassLoader l) {
        if (l == null) return "the bootstrap class loader";
        return l.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(l));
    }

    /**
     * A class the TRACE scope asked for by name and a veto refused. Same shape as
     * {@link #vetoed}, separate counters: the two scopes are configured separately and an
     * operator debugging "nothing was traced" must not have to read coverage counters.
     */
    private void traceVetoed(String internalName, String prefix, String why) {
        TraceHealth.skip("ignoredPrefix");
        TraceHealth.skip("inScopeVetoed");
        if (TraceHealth.warnOnce("traceInScopeVeto:" + prefix)) {
            Log.warn(internalName + " matches ax.trace.include.packages but was NOT traced: the "
                    + "prefix '" + prefix + "' (" + why + ") vetoes it. Counted as "
                    + "skipped.inScopeVetoed. Absence of frames for this class is NOT evidence "
                    + "that it did not run.");
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

    /**
     * One jar, one transformer, one set of output bytes — so one dump. {@code ax.dump.dir} wins
     * and {@code ax.trace.dump.dir} is honoured as an alias, because a merged class carries both
     * tiers and writing it twice under two names would invite a reader to diff two identical
     * files and conclude the tiers are separable.
     */
    private void dump(String internalName, byte[] bytes) {
        String dir = options.dumpDir;
        if (dir == null || dir.isEmpty()) dir = options.trace.dumpDir;
        if (dir == null || dir.isEmpty()) return;
        try {
            File f = new File(dir, internalName.replace('/', '.') + ".class");
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
