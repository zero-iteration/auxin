package io.auxin.trace.instrument;

import io.auxin.trace.config.TraceOptions;
import io.auxin.trace.runtime.TraceHealth;
import io.auxin.trace.util.TLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Installs trace probes at INITIAL CLASS LOAD. Registered with the one-argument
 * {@code addTransformer(t)} — <b>canRetransform = false</b> — for the same two reasons
 * {@code ProbeInstaller} is, and for a third of its own.
 *
 * <ol>
 *   <li>The JVM runs retransformation-incapable transformers first, so this runs ahead of any
 *       capable agent regardless of {@code -javaagent} order.</li>
 *   <li>On any later retransform the JVM replays this transformer's cached output as the input to
 *       capable transformers, so trace probes survive another agent's retransform batch — which
 *       includes <b>ax-agent's own Tier-1b stripper</b>. Tier-1b matches tier-1 probe shapes and
 *       nothing emitted here looks like one, so a strip removes coverage probes and leaves trace
 *       probes in place.</li>
 *   <li><b>There is nothing here to retransform.</b> A trace probe cannot be stripped, because
 *       you cannot know in advance which request will be traced. So this agent never asks for the
 *       capability, and the jar manifest says {@code Can-Retransform-Classes: false}. That is the
 *       honest encoding of the trade in TRADE-OFFS.md, not an omission.</li>
 * </ol>
 *
 * <p>Therefore {@code classBeingRedefined != null} MUST return null.
 */
public final class TraceInstaller implements ClassFileTransformer {

    private final TraceOptions options;
    private final TraceEmitter emitter;

    public TraceInstaller(TraceOptions options, EntrySignatures entries) {
        this.options = options;
        this.emitter = new TraceEmitter(options, entries);
    }

    /**
     * Resolves every class on the transform path, before this transformer is registered.
     *
     * <p>Called by {@code TraceAgent.premain}. A class reached for the first time from inside
     * {@code transform()} is a re-entrant load on the same thread, which the JVM answers with
     * {@code LinkageError: attempted duplicate class definition}. The smoke suite produced
     * exactly that on its first run, for {@code TraceIgnoreRules} — the class holding the
     * hard-veto list that would have prevented it.
     *
     * <p>It does the real thing (parse, emit, write) to a class that is out of every scope, so
     * ASM's reader, writer and tree nodes are all genuinely loaded rather than merely named.
     */
    public void warmUp(byte[] anyClassBytes) {
        try {
            TraceIgnoreRules.hardVeto("java/lang/Object");
            TraceIgnoreRules.generated("x/Y");
            TraceIgnoreRules.ownRuntime();
            TraceLoaderVisibility.visibleFrom(TraceInstaller.class.getClassLoader());
            if (anyClassBytes == null) return;
            ClassNode cn = new ClassNode();
            new ClassReader(anyClassBytes).accept(cn, 0);
            // Both scopes false: the emitter walks every method and instruments none, which is
            // all that is needed to resolve it and everything it touches.
            emitter.instrument(cn, false, false);
            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            cw.toByteArray();
        } catch (Throwable t) {
            TLog.debug("transform-path warm-up failed; the first transform may be slower", t);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // FAIL OPEN, ABSOLUTELY. The JVM silently ignores a transformer that throws.
        try {
            return doTransform(loader, internalName, classBeingRedefined, classfileBuffer);
        } catch (Throwable t) {
            TraceHealth.TRANSFORM_FAILURES.incrementAndGet();
            TraceHealth.skip("transformFailure");
            TLog.debug("trace transform failed for " + internalName, t);
            return null;
        }
    }

    private byte[] doTransform(ClassLoader loader, String internalName,
                               Class<?> classBeingRedefined, byte[] buffer) {
        // ---- pass 1: NAME ONLY. No parsing. This is the startup-CPU lever (A11/C13): parsing
        // the classes we were always going to reject cost ax-agent +254 ms of CPU in G4, which
        // under a 1-CPU cgroup is +254 ms of WALL. ----
        if (classBeingRedefined != null) return null;       // HARD RULE
        if (internalName == null || buffer == null) return null;
        if (!options.enabled) return null;

        final String hardVeto = TraceIgnoreRules.hardVeto(internalName);
        if (hardVeto != null) {
            // G5-BUG-1's precedence rule: hard veto > explicit scope > soft veto, and a rejection
            // of a class the OPERATOR asked for by name is an event they must see. Our own
            // runtime is the exception -- resolving TraceHealth from inside the transform of a
            // trace class would be a recursive class load.
            if (!TraceIgnoreRules.isOwnRuntime(hardVeto) && inAnyScope(internalName)) {
                vetoed(internalName, hardVeto, "never-instrumentable");
            }
            return null;
        }
        final boolean inTrace = options.scope.included(internalName);
        final boolean inEntry = options.entryScope.included(internalName) || inTrace;
        if (!inTrace && !inEntry) return null;              // DEFAULT DENY
        if (TraceIgnoreRules.generated(internalName)) {
            TraceHealth.skip("generatedClass");
            return null;
        }
        if (options.scope.excluded(internalName)) {
            TraceHealth.skip("packageKillSwitch");
            return null;
        }
        final String foreignVeto = TraceIgnoreRules.foreignVeto(internalName);
        if (foreignVeto != null) {
            final String matched = options.scope.matchedPrefix(internalName);
            if (!TraceIgnoreRules.scopeWins(matched, foreignVeto)) {
                vetoed(internalName, foreignVeto, "another agent's runtime");
                return null;
            }
        }
        // Still pass 1, still no parsing: one cached question per class loader. A loader that
        // cannot resolve TraceRuntime would raise NoClassDefFoundError inside a business method,
        // and there is no bridge for a per-invocation call (see TraceLoaderVisibility).
        if (!TraceLoaderVisibility.visibleFrom(loader)) {
            TraceHealth.skip("agentNotVisible");
            if (TraceHealth.warnOnce("agentNotVisible")) {
                TLog.warn("classes loaded by " + describe(loader) + " cannot resolve "
                        + "io.auxin.trace.runtime.TraceRuntime, so they are NOT traced (they are "
                        + "never broken). Counted as agentNotVisible. There is no java.lang "
                        + "bridge for a per-invocation call; see TRADE-OFFS.md.");
            }
            return null;
        }

        // ---- pass 2: parse and rewrite ----
        ClassNode cn = new ClassNode();
        // flags 0: frames are read as FrameNodes and written straight back out. SKIP_FRAMES would
        // drop them and ClassWriter(0) would emit none -> VerifyError on v50+. EXPAND_FRAMES is
        // deliberately NOT used: this emitter adds no local, so no existing frame ever has to
        // learn anything, and compressed-only keeps the handler frame a plain F_FULL.
        new ClassReader(buffer).accept(cn, 0);

        TraceEmitter.Result r = emitter.instrument(cn, inTrace, inEntry);
        if (!r.changed()) {
            if (r.skipReason != null) TraceHealth.skip(r.skipReason);
            return null;
        }

        ClassWriter cw = new ClassWriter(0);   // NEVER COMPUTE_FRAMES (it loads classes)
        cn.accept(cw);
        byte[] out = cw.toByteArray();

        TraceHealth.CLASSES_INSTRUMENTED.incrementAndGet();
        if (TLog.debugEnabled()) {
            TLog.debug("traced " + internalName + " methods=" + r.methods
                    + " entries=" + r.entries + " arms=" + r.arms
                    + " obs=" + r.observations + " wrappedCallSites=" + r.wrappedCallSites
                    + " handlersOmitted=" + r.handlersOmitted
                    + " bytes " + buffer.length + " -> " + out.length);
        }
        dump(internalName, out);
        return out;
    }

    private boolean inAnyScope(String internalName) {
        return options.scope.included(internalName) || options.entryScope.included(internalName);
    }

    private void vetoed(String internalName, String prefix, String why) {
        TraceHealth.skip("ignoredPrefix");
        TraceHealth.skip("inScopeVetoed");
        if (TraceHealth.warnOnce("inScopeVeto:" + prefix)) {
            TLog.warn(internalName + " matches ax.trace.include.packages but was NOT traced: the "
                    + "prefix '" + prefix + "' (" + why + ") vetoes it. Counted as "
                    + "skipped.inScopeVetoed. Absence of frames for this class is NOT evidence "
                    + "that it did not run.");
        } else {
            TLog.debug("in-scope class vetoed by '" + prefix + "': " + internalName);
        }
    }

    private static String describe(ClassLoader l) {
        if (l == null) return "the bootstrap class loader";
        return l.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(l));
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
            TLog.debug("class dump failed", t);
        }
    }
}
