package io.auxin.trace.instrument;

import io.auxin.trace.config.TraceOptions;
import io.auxin.trace.runtime.SiteRegistry;
import io.auxin.trace.runtime.TraceHealth;
import io.auxin.trace.util.TLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Trace probe emission. Same machinery as {@code io.auxin.agent.instrument.ProbeEmitter} — raw
 * ASM, {@code ClassWriter(0)}, frames written by hand, COMPUTE_FRAMES never used because it
 * loads classes — with a different probe body. Every frame hazard ProbeEmitter documents applies
 * here and the resolutions are the same ones, credited where they are used.
 *
 * <h3>What is emitted, in one place</h3>
 * <pre>
 *   [entry method only]  ALOAD requestArg; INVOKESTATIC TraceGate.begin(Ljava/lang/Object;)V
 *
 *   push frameId; INVOKESTATIC TraceRuntime.enter(I)V
 *   [per reference param]   ALOAD  p; push obsId; INVOKESTATIC TraceRuntime.obsRef(Ljava/lang/Object;I)V
 *   [per int-ish param]     ILOAD  p; push obsId; INVOKESTATIC TraceRuntime.obsInt(II)V
 *   [per long param]        LLOAD  p; push obsId; INVOKESTATIC TraceRuntime.obsLong(JI)V
 *   [per float/double]      DLOAD  p; push obsId; INVOKESTATIC TraceRuntime.obsDouble(DI)V
 *   [parallelStream only]   INVOKESTATIC TraceRuntime.commonPoolArm()V
 *  tryStart:
 *      ... body, with a branch-arm probe before each selected conditional ...
 *
 *      [before every xRETURN]
 *        &lt;DUP | DUP2&gt;; push obsId; INVOKESTATIC TraceRuntime.ret*(...)V   // the return value
 *        &lt;re-read each reference param&gt;; push obsId; obsRef(...)          // the EXIT size
 *        [parallelStream only]  INVOKESTATIC TraceRuntime.commonPoolDisarm()V
 *        push frameId; INVOKESTATIC TraceRuntime.exit(I)V
 *        [entry method only]    INVOKESTATIC TraceGate.end()V
 *  tryEnd:
 *  handler:  [stack: Throwable]
 *        DUP; push frameId; INVOKESTATIC TraceRuntime.threw(Ljava/lang/Throwable;I)V
 *        [parallelStream only]  commonPoolDisarm
 *        push frameId; INVOKESTATIC TraceRuntime.exit(I)V
 *        [entry method only]    TraceGate.end()
 *        ATHROW
 * </pre>
 *
 * <h3>Stack map frames: exactly one per instrumented method, and it is the handler's</h3>
 * <b>Nothing else emitted here needs a frame at all.</b> No probe adds a local, no probe adds a
 * branch, and the operand duplication ({@code DUP}/{@code DUP2}) is balanced before the next
 * instruction. The only construct that needs the verifier told anything is the
 * {@code catch (Throwable)} handler, and it is written by hand as one entry declaring the
 * <b>receiver and the descriptor's arguments</b> (then {@code TOP} for every remaining slot) and
 * a single {@code java/lang/Throwable} on the stack. It declared zero locals once, copying
 * {@code ProbeEmitter.emitEdgeRoot}, and that produced a {@code VerifyError} the moment another
 * handler range covered this block — see the comment at the frame itself.
 *
 * <h3>The four frame cases ax-agent had to learn, and what this module does about each</h3>
 * <ol>
 *   <li><b>A frame already at offset 0</b> (a method starting with a loop header). Not an issue
 *       here: this emitter never puts a frame at offset 0, only instructions, so there is never
 *       a second entry at that offset. ({@code ProbeEmitter.entryFrameLabel} exists because
 *       tier-1's read-then-store probe DOES need a merge point there.)</li>
 *   <li><b>Expanded frames.</b> ASM cannot mix {@code F_NEW} with compressed frames in one
 *       method, and since SCOPE-v3.1 this tier shares {@code ProbeInstaller}'s single read of
 *       the class — which uses EXPAND_FRAMES whenever the class carries a tier-2 method. So the
 *       handler entry is written {@code F_NEW} when the method already carries an expanded frame
 *       and {@code F_FULL} otherwise, read off the method rather than assumed. This tier still
 *       adds no local, so no pre-existing frame ever has to learn anything.</li>
 *   <li><b>{@code <init>}.</b> A {@code catch (Throwable)} handler inside a constructor has to
 *       merge against a state where {@code this} may be {@code uninitializedThis}, and a frame
 *       with zero locals asserts {@code flagThisUninit = false}. That is a VerifyError, so
 *       {@code <init>} gets <b>everything except the handler</b> — entry, exit, observations and
 *       branch arms all work, and an exception escaping a constructor simply leaves the frame
 *       open for {@code TraceRuntime}'s pop-to-repair. Counted as
 *       {@code throwCaptureUnsupportedInit}.</li>
 *   <li><b>Class file version exactly 50.</b> A StackMapTable is optional at 50 and adding one to
 *       a method that has none makes HotSpot's type-checker fail and fail over to the inference
 *       verifier, per class. ax-agent's answer for tier-1 is to keep the frameless probe shape;
 *       ours is the same in spirit — at exactly 50 the handler is omitted and counted as
 *       {@code throwCaptureUnsupportedV50}. Below 50 there is no StackMapTable at all, so the
 *       handler is emitted with no frame and works.</li>
 * </ol>
 *
 * <h3>Branch arms — the genuinely new bytecode</h3>
 * Nothing off the shelf records <i>which arm a conditional took on one specific request</i>:
 * JaCoCo's branch coverage is JVM-global, value-free and accumulates for the life of the process.
 * The trick that makes it cheap is to record the OPERANDS rather than the arm:
 * <pre>
 *   IFEQ      -&gt; DUP;  push siteId; INVOKESTATIC armI(II)V         then the original IFEQ
 *   IF_ICMPLT -&gt; DUP2; push siteId; INVOKESTATIC armII(III)V
 *   IFNULL    -&gt; DUP;  push siteId; INVOKESTATIC armA(Ljava/lang/Object;I)V
 *   IF_ACMPEQ -&gt; DUP2; push siteId; INVOKESTATIC armAA(Ljava/lang/Object;Ljava/lang/Object;I)V
 *   TABLESWITCH -&gt; DUP; push siteId; INVOKESTATIC armSwitch(II)V
 * </pre>
 * The <i>opcode</i> is registered in the {@link SiteRegistry} at transform time, so which arm the
 * operands imply is computed when the document is built, on the dispatcher thread. Consequences:
 * <b>no code at any jump target</b> (a merge point, and therefore a frame), <b>no local</b>, and
 * the operand values themselves — which are frequently the whole answer ("the flag read 0, so it
 * took the else"). For a reference comparison the operands are reduced to one bit
 * ({@code null}-ness, or identity) inside {@code TraceRuntime} before anything is stored, so no
 * object is retained and no value is recorded.
 *
 * <h3>Two conditionals this must NOT record</h3>
 * <ul>
 *   <li>{@code IFNE} whose operand came from a {@code BALOAD}: that is a coverage probe —
 *       ax-agent's own read-then-store shape, or JaCoCo's. Recording it would report auxin's
 *       instrumentation as an application branch. Refused unconditionally, at every mode.</li>
 *   <li>In the default {@code predicates} mode, a conditional whose operands came only from
 *       local loads, constants or {@code ARRAYLENGTH}: loop counters and bounds checks. They are
 *       where the 5,825-invocation volume problem comes from and they explain nothing.</li>
 * </ul>
 */
public final class TraceEmitter {

    static final String RUNTIME = "io/auxin/trace/runtime/TraceRuntime";
    static final String GATE = "io/auxin/trace/runtime/TraceGate";

    private static final String OBJ = "Ljava/lang/Object;";
    private static final int FRAMES_MIN_VERSION = 50;
    private static final int FRAMES_SAFE_VERSION = 51;

    public static final class Result {
        public int methods;
        public int entries;
        public int wrappedCallSites;
        public int arms;
        public int observations;
        public int handlersOmitted;
        public String skipReason;

        public boolean changed() { return methods > 0 || entries > 0 || wrappedCallSites > 0; }
    }

    private final TraceOptions options;
    private final EntrySignatures entries;
    private final boolean armsAll;
    private final boolean armsNone;

    public TraceEmitter(TraceOptions options, EntrySignatures entries) {
        this.options = options;
        this.entries = entries;
        this.armsAll = TraceOptions.BRANCHES_ALL.equals(options.branches);
        this.armsNone = TraceOptions.BRANCHES_NONE.equals(options.branches);
    }

    /** Mutates {@code cn} in place. */
    public Result instrument(ClassNode cn, boolean inTraceScope, boolean inEntryScope) {
        Result r = new Result();
        final int version = cn.version & 0xFFFF;
        final String dotted = cn.name.replace('/', '.');

        for (int i = 0; i < cn.methods.size(); i++) {
            MethodNode m = cn.methods.get(i);
            try {
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                if (m.instructions == null || m.instructions.size() == 0) continue;
                if ("<clinit>".equals(m.name)) continue;       // PLAN-v2: never <clinit>
                if (hasJsr(m)) {
                    TraceHealth.skip("jsrMethod");
                    continue;
                }
                if (!instrumentable(m)) continue;
                if (isTrivial(cn, m)) {
                    TraceHealth.skip("trivialAccessor");
                    continue;
                }

                final boolean isEntry = inEntryScope
                        && entries.requestArg(m.name, m.desc) >= 0;
                if (!inTraceScope && !isEntry) continue;

                if (emit(cn, m, dotted, version, isEntry, inTraceScope, r)) {
                    r.methods++;
                    if (isEntry) r.entries++;
                }
            } catch (Throwable t) {
                // FAIL OPEN AT METHOD GRANULARITY. A half-instrumented method is a VerifyError,
                // which is an outage; a skipped method is a gap in one trace.
                TraceHealth.skip("methodEmissionFailed");
                TLog.debug("trace emission failed for " + cn.name + "#" + m.name, t);
            }
        }
        if (!r.changed()) r.skipReason = "nothingToInstrument";
        return r;
    }

    /**
     * Bridge and synthetic methods are skipped — <b>except</b> a lambda body.
     *
     * <p>{@code lambda$foo$0} is {@code ACC_SYNTHETIC} and {@code ACC_PRIVATE}, and it is where
     * the work inside a {@code parallelStream} pipeline, a {@code CompletableFuture} chain and
     * most executor tasks actually lives. Skipping synthetics wholesale — the correct rule for
     * COVERAGE, where a synthetic method is not source a human can delete — would make this
     * module blind to exactly the concurrency it exists to follow.
     */
    private static boolean instrumentable(MethodNode m) {
        if ((m.access & Opcodes.ACC_BRIDGE) != 0) return false;
        if ((m.access & Opcodes.ACC_SYNTHETIC) == 0) return true;
        return m.name.startsWith("lambda$");
    }

    /**
     * THE C51 RULE, applied to frames instead of probes.
     *
     * <p>{@code docs/CONTRACTS.md} §1 already states it for coverage: "single-instruction bodies,
     * constant-returning accessors, foldable {@code static final} initializers, empty methods"
     * are marked {@code dynamicallyObservable: false} because TOSEM 2022 shows they cannot be
     * covered dynamically, and ax-agent refuses to put a probe on one — "cost without signal".
     * The same methods are cost without signal in a trace, for a different reason: a frame for
     * {@code getStops()} tells a reader nothing they did not already have from the object's
     * projection, and there are thousands of them.
     *
     * <p>Measured, on the smoke target: {@code Fare#getStops}, {@code Fare#isRefundable} and
     * {@code Fare$Carrier#values} alone accounted for <b>519 of 865 frames</b> in the first
     * document that came out of it. This rule removes them.
     *
     * <p>The tracer has no build manifest, so it cannot read the C51 flag; it re-derives the same
     * classification from the instruction list, which is the cheap half of what {@code ax-static}
     * does. It is deliberately conservative — a method one instruction more complex than these
     * shapes IS instrumented.
     */
    static boolean isTrivial(ClassNode cn, MethodNode m) {
        // Compiler-generated enum plumbing. Not marked ACC_SYNTHETIC, so the synthetic filter
        // misses it, and values() clones an array on every call -- which would otherwise be one
        // frame per enum lookup.
        if ((cn.access & Opcodes.ACC_ENUM) != 0
                && (("values".equals(m.name) && m.desc.startsWith("()["))
                    || ("valueOf".equals(m.name)
                        && m.desc.startsWith("(Ljava/lang/String;)")))) {
            return true;
        }
        int[] ops = new int[6];
        int n = 0;
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i.getOpcode() < 0) continue;                 // label, frame, line number
            if (n == ops.length) return false;               // too long to be trivial
            ops[n++] = i.getOpcode();
        }
        if (n == 0) return true;
        final int last = ops[n - 1];
        final boolean returns = last >= Opcodes.IRETURN && last <= Opcodes.RETURN;
        if (!returns) return false;
        if (n == 1) return true;                             // an empty void method
        if (n == 2) {
            // `return <const>;`  or  `return SOME_STATIC;`
            return isConst(ops[0]) || ops[0] == Opcodes.GETSTATIC;
        }
        if (n == 3) {
            // `return this.field;`  or  `return SOME_STATIC_CAST;`
            if (ops[0] == Opcodes.ALOAD && ops[1] == Opcodes.GETFIELD) return true;
            return ops[0] == Opcodes.GETSTATIC && ops[1] == Opcodes.CHECKCAST;
        }
        if (n == 4) {
            // `return (T) this.field;` -- generic erasure puts a CHECKCAST on a field read
            return ops[0] == Opcodes.ALOAD && ops[1] == Opcodes.GETFIELD
                    && ops[2] == Opcodes.CHECKCAST;
        }
        return false;
    }

    private static boolean isConst(int op) {
        if (op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1) return true;
        return op == Opcodes.BIPUSH || op == Opcodes.SIPUSH || op == Opcodes.LDC;
    }

    private boolean emit(ClassNode cn, MethodNode m, String dotted, int version, boolean wantEntry,
                         boolean inTraceScope, Result r) {
        final boolean isInit = "<init>".equals(m.name);
        final boolean frameNeeded = version >= FRAMES_MIN_VERSION;
        final boolean frameSafe = version >= FRAMES_SAFE_VERSION || version < FRAMES_MIN_VERSION;
        final boolean handlerPossible = !isInit && frameSafe;

        // THE ONE CASE WHERE A MISSING HANDLER IS NOT SURVIVABLE. TraceGate.end() is what
        // decrements the global gate; if an exception can escape the ENTRY method without
        // running it, ACTIVE stays non-zero for the life of the JVM and every probe everywhere
        // pays a ThreadLocal read for ever -- the exact failure mode EdgeRuntime's javadoc calls
        // "the one cost this design cannot afford". So an entry method that cannot carry a
        // handler is not instrumented as an entry AT ALL, loudly.
        final boolean isEntry = wantEntry && handlerPossible;
        if (wantEntry && !isEntry) {
            TraceHealth.skip("entryNeedsThrowHandler");
            if (TraceHealth.warnOnce("entryNeedsThrowHandler:" + dotted)) {
                TLog.warn(dotted + "#" + m.name + " matches a servlet entry signature but cannot "
                        + "carry a catch(Throwable) handler (class file version " + version
                        + (isInit ? ", constructor" : "") + "), and an entry without one could "
                        + "leak the trace gate. It is NOT instrumented as an activation point. "
                        + "No request will be traced through it.");
            }
            if (!inTraceScope) return false;
        }
        final boolean escapes = inTraceScope && options.parallelStreamWindow
                && usesParallelStream(m);
        final int line = firstLine(m);

        final int frameId = SiteRegistry.registerFrame(dotted, m.name, m.desc, line, escapes);
        if (frameId < 0) return false;

        // ---- collect everything BEFORE mutating, so the walk sees only original instructions --
        final List<AbstractInsnNode> returns = collectReturns(m);
        final List<AbstractInsnNode> conditionals =
                inTraceScope && !armsNone ? collectConditionals(m) : emptyList();
        final List<MethodInsnNode> callSites =
                inTraceScope && options.propagateExecutors ? collectSubmits(m) : emptyMethodList();

        // ---- the prologue ----
        InsnList pre = new InsnList();
        if (isEntry) {
            // The request is argument 0. On a non-static method that is local slot 1.
            int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            pre.add(new VarInsnNode(Opcodes.ALOAD, slot));
            pre.add(call(GATE, "begin", "(" + OBJ + ")V"));
        }
        push(pre, frameId);
        pre.add(call(RUNTIME, "enter", "(I)V"));
        int obsAdded = 0;
        List<int[]> exitParams = new ArrayList<int[]>();   // {slot, obsId}
        if (inTraceScope && options.observeParams) {
            obsAdded = emitParamObservations(m, frameId, pre, exitParams);
        }
        if (escapes) pre.add(call(RUNTIME, "commonPoolArm", "()V"));

        final LabelNode tryStart = new LabelNode();
        final LabelNode tryEnd = new LabelNode();
        final LabelNode handler = new LabelNode();
        pre.add(tryStart);
        m.instructions.insert(pre);

        // ---- the epilogue, before every return ----
        final int retObsId = inTraceScope && options.observeReturns
                ? returnObsId(frameId, m.desc) : -1;
        for (int i = 0; i < returns.size(); i++) {
            AbstractInsnNode ret = returns.get(i);
            InsnList post = new InsnList();
            if (retObsId >= 0) emitReturnObservation(post, ret.getOpcode(), m.desc, retObsId);
            if (options.observeParamsAtExit) {
                for (int k = 0; k < exitParams.size(); k++) {
                    int[] p = exitParams.get(k);
                    post.add(new VarInsnNode(Opcodes.ALOAD, p[0]));
                    push(post, p[1]);
                    post.add(call(RUNTIME, "obsRef", "(" + OBJ + "I)V"));
                }
            }
            if (escapes) post.add(call(RUNTIME, "commonPoolDisarm", "()V"));
            push(post, frameId);
            post.add(call(RUNTIME, "exit", "(I)V"));
            if (isEntry) post.add(call(GATE, "end", "()V"));
            m.instructions.insertBefore(ret, post);
        }

        // ---- branch arms ----
        int armCount = 0;
        for (int i = 0; i < conditionals.size(); i++) {
            if (emitArm(m, frameId, conditionals.get(i))) armCount++;
        }

        // ---- executor call-site wrapping ----
        int wrapped = 0;
        for (int i = 0; i < callSites.size(); i++) {
            if (emitWrap(m, callSites.get(i))) wrapped++;
        }

        // ---- the throw handler ----
        boolean handlerEmitted = false;
        if (isInit) {
            TraceHealth.skip("throwCaptureUnsupportedInit");
            r.handlersOmitted++;
        } else if (!frameSafe) {
            TraceHealth.skip("throwCaptureUnsupportedV50");
            r.handlersOmitted++;
        } else {
            InsnList post = new InsnList();
            post.add(tryEnd);
            post.add(handler);
            if (frameNeeded) {
                // The receiver and the declared arguments, then TOP for every remaining slot,
                // and one java/lang/Throwable on the stack.
                //
                // >>> F_NEW OR F_FULL, decided per method (SCOPE-v3.1). Both encode the identical
                // full_frame on the wire, but ASM cannot MIX the two within one method. While
                // this was a separate agent the answer was always F_FULL, because it always read
                // with flags 0. In the merged transformer ProbeInstaller reads with
                // EXPAND_FRAMES whenever the class carries a tier-2 method, and tier-2 has
                // already added an F_NEW handler frame of its own by the time we get here -- so
                // the form has to be read off the method rather than assumed. ax-agent hit the
                // mirror image of this with ax.tier2.enabled=false, where an unconditional F_NEW
                // silently refused every edge root.
                //
                // >>> WHY NOT ZERO LOCALS. It was zero locals first, copying
                // ProbeEmitter.emitEdgeRoot's argument -- the handler reads no local, and an
                // undeclared local is `top`, which everything is assignable TO. That is sound in
                // isolation and it produced a VerifyError the moment both agents ran:
                //
                //   traceapp/SearchFilter.doFilter @141: Type top (current frame, locals[0]) is
                //   not assignable to 'traceapp/SearchFilter' (stack map, locals[0])
                //
                // ax-agent's tier-2 wraps the method in its own catch(Throwable), and because its
                // tryEnd is appended at the END of the instruction list, ITS range covers OUR
                // handler block. So control can flow from inside our handler to ITS handler,
                // whose frame names the receiver type -- and our frame had already erased
                // locals[0] to `top`. `top` is assignable-to, not assignable-from.
                //
                // emitEdgeRoot gets away with zero locals only because it is emitted LAST within
                // one emitter, so its frame sits outside every range. A separate agent cannot
                // rely on being last. Declaring the real layout is the version that composes:
                // TOP where a slot holds something we do not name is still a widening, and
                // naming the receiver and the arguments cannot be wrong -- they come from the
                // descriptor, and <init> (the one place local 0 is uninitializedThis) never
                // reaches here.
                Object[] locals = handlerFrameLocals(cn, m);
                post.add(new FrameNode(hasExpandedFrame(m) ? Opcodes.F_NEW : Opcodes.F_FULL,
                        locals.length, locals, 1, new Object[]{"java/lang/Throwable"}));
            }
            post.add(new InsnNode(Opcodes.DUP));
            push(post, frameId);
            post.add(call(RUNTIME, "threw", "(Ljava/lang/Throwable;I)V"));
            if (escapes) post.add(call(RUNTIME, "commonPoolDisarm", "()V"));
            push(post, frameId);
            post.add(call(RUNTIME, "exit", "(I)V"));
            if (isEntry) post.add(call(GATE, "end", "()V"));
            post.add(new InsnNode(Opcodes.ATHROW));
            m.instructions.add(post);

            if (m.tryCatchBlocks == null) m.tryCatchBlocks = new ArrayList<TryCatchBlockNode>();
            // Appended LAST so that, when ax-agent has also instrumented this method, its
            // handler (added first) still classifies the error and then rethrows from inside
            // our range -- the same ordering argument ProbeEmitter.emitEdgeRoot makes.
            m.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, null));
            handlerEmitted = true;
        }
        if (!handlerEmitted) {
            // The try range is unused, but tryStart is already in the instruction list and a
            // label with no reference is legal and free. Removing it would mean another walk.
            m.instructions.add(tryEnd);
        }

        // Worst case added depth: DUP2 (2) + the pushed id (1). Never a local.
        m.maxStack = Math.max(m.maxStack + 3, 6);
        r.arms += armCount;
        r.observations += obsAdded + (retObsId >= 0 ? 1 : 0);
        r.wrappedCallSites += wrapped;
        TraceHealth.METHODS_INSTRUMENTED.incrementAndGet();
        if (isEntry) TraceHealth.ENTRIES_INSTRUMENTED.incrementAndGet();
        if (wrapped > 0) TraceHealth.CALL_SITES_WRAPPED.addAndGet(wrapped);
        return true;
    }

    // ---------------- parameter and return observations ----------------

    private int emitParamObservations(MethodNode m, int frameId, InsnList pre,
                                      List<int[]> exitParams) {
        final Type[] args = Type.getArgumentTypes(m.desc);
        int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        int n = 0;
        for (int i = 0; i < args.length; i++) {
            final Type t = args[i];
            final String name = "arg" + i;
            final int obsId = SiteRegistry.registerObs(frameId, SiteRegistry.OBS_PARAM_ENTRY, i,
                    t.getDescriptor(), name);
            if (obsId < 0) break;
            switch (t.getSort()) {
                case Type.OBJECT:
                case Type.ARRAY:
                    pre.add(new VarInsnNode(Opcodes.ALOAD, slot));
                    push(pre, obsId);
                    pre.add(call(RUNTIME, "obsRef", "(" + OBJ + "I)V"));
                    if (options.observeParamsAtExit) {
                        // The SAME slot, re-read before every return. This is what turns
                        // "126 fares in" into "126 in, 94 out": a method that filters a
                        // collection IN PLACE shows the drop here and nowhere else.
                        //
                        // Safe because javac never reuses a PARAMETER slot for a local of a
                        // different type, and safe even if it did: the value is passed as
                        // Object and every decision about it is made at runtime, so the worst
                        // case is an uninteresting observation rather than a type error.
                        int exitId = SiteRegistry.registerObs(frameId,
                                SiteRegistry.OBS_PARAM_EXIT, i, t.getDescriptor(), name);
                        if (exitId >= 0) exitParams.add(new int[]{slot, exitId});
                    }
                    break;
                case Type.LONG:
                    pre.add(new VarInsnNode(Opcodes.LLOAD, slot));
                    push(pre, obsId);
                    pre.add(call(RUNTIME, "obsLong", "(JI)V"));
                    break;
                case Type.DOUBLE:
                    pre.add(new VarInsnNode(Opcodes.DLOAD, slot));
                    push(pre, obsId);
                    pre.add(call(RUNTIME, "obsDouble", "(DI)V"));
                    break;
                case Type.FLOAT:
                    pre.add(new VarInsnNode(Opcodes.FLOAD, slot));
                    pre.add(new InsnNode(Opcodes.F2D));
                    push(pre, obsId);
                    pre.add(call(RUNTIME, "obsDouble", "(DI)V"));
                    break;
                default:                                   // boolean, byte, char, short, int
                    pre.add(new VarInsnNode(Opcodes.ILOAD, slot));
                    push(pre, obsId);
                    pre.add(call(RUNTIME, "obsInt", "(II)V"));
                    break;
            }
            slot += t.getSize();
            n++;
        }
        return n;
    }

    private static int returnObsId(int frameId, String desc) {
        Type t = Type.getReturnType(desc);
        if (t.getSort() == Type.VOID) return -1;
        return SiteRegistry.registerObs(frameId, SiteRegistry.OBS_RETURN, -1, t.getDescriptor(),
                "return");
    }

    /**
     * Duplicates the value already on the stack and hands the copy to the runtime. Balanced:
     * after the call the stack is exactly what the {@code xRETURN} expects, so no frame, no
     * local and no reordering of the original instruction.
     */
    private static void emitReturnObservation(InsnList post, int opcode, String desc, int obsId) {
        switch (opcode) {
            case Opcodes.ARETURN:
                post.add(new InsnNode(Opcodes.DUP));
                push(post, obsId);
                post.add(call(RUNTIME, "obsRef", "(" + OBJ + "I)V"));
                break;
            case Opcodes.IRETURN:
                post.add(new InsnNode(Opcodes.DUP));
                push(post, obsId);
                post.add(call(RUNTIME, "obsInt", "(II)V"));
                break;
            case Opcodes.LRETURN:
                post.add(new InsnNode(Opcodes.DUP2));
                push(post, obsId);
                post.add(call(RUNTIME, "obsLong", "(JI)V"));
                break;
            case Opcodes.DRETURN:
                post.add(new InsnNode(Opcodes.DUP2));
                push(post, obsId);
                post.add(call(RUNTIME, "obsDouble", "(DI)V"));
                break;
            case Opcodes.FRETURN:
                post.add(new InsnNode(Opcodes.DUP));
                post.add(new InsnNode(Opcodes.F2D));
                push(post, obsId);
                post.add(call(RUNTIME, "obsDouble", "(DI)V"));
                break;
            default:
                break;                                   // RETURN: nothing on the stack
        }
    }

    // ---------------- branch arms ----------------

    private boolean emitArm(MethodNode m, int frameId, AbstractInsnNode insn) {
        final int op = insn.getOpcode();
        final String reason = armReason(insn, op);
        if (reason == null) return false;
        final int line = lineOf(insn);
        final int armId = SiteRegistry.registerArm(frameId, op, line, reason);
        if (armId < 0) return false;

        InsnList l = new InsnList();
        switch (op) {
            case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT:
            case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
                l.add(new InsnNode(Opcodes.DUP));
                push(l, armId);
                l.add(call(RUNTIME, "armI", "(II)V"));
                break;
            case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
                l.add(new InsnNode(Opcodes.DUP2));
                push(l, armId);
                l.add(call(RUNTIME, "armII", "(III)V"));
                break;
            case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                l.add(new InsnNode(Opcodes.DUP));
                push(l, armId);
                l.add(call(RUNTIME, "armA", "(" + OBJ + "I)V"));
                break;
            case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                l.add(new InsnNode(Opcodes.DUP2));
                push(l, armId);
                l.add(call(RUNTIME, "armAA", "(" + OBJ + OBJ + "I)V"));
                break;
            case Opcodes.TABLESWITCH: case Opcodes.LOOKUPSWITCH:
                l.add(new InsnNode(Opcodes.DUP));
                push(l, armId);
                l.add(call(RUNTIME, "armSwitch", "(II)V"));
                break;
            default:
                return false;
        }
        m.instructions.insertBefore(insn, l);
        return true;
    }

    /**
     * WHICH conditionals are worth recording, and why the default is not "all".
     *
     * <p>{@code null} means "do not record". The volume argument is concrete: one search in the
     * field trial was 5,825 invocations across 196 methods, and full-fidelity branch recording
     * over that is unreadable by a human and useless to an agent. The conditionals that explain
     * an outcome are the ones reading a flag, a config value or a predicate's result; the ones
     * that do not are loop counters and bounds checks.
     */
    private String armReason(AbstractInsnNode insn, int op) {
        AbstractInsnNode prev = previousReal(insn);
        // A coverage probe, never an application branch. ax-agent's tier-1 read-then-store shape
        // is `ALOAD slot; push idx; BALOAD; IFNE end`, and JaCoCo's is the same modulo the array
        // source. Refused at EVERY mode, including `all`.
        if (op == Opcodes.IFNE && prev != null && prev.getOpcode() == Opcodes.BALOAD) return null;

        if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) return "null";
        if (armsAll) return "all";

        // Walk back over the instructions that produced the operands, and STOP AT THE BASIC
        // BLOCK BOUNDARY.
        //
        // Bug found by the smoke suite: without the boundary check, `for (int i = 0, n = xs.size();
        // i < n; i++)` was recorded. javac puts the condition at the BOTTOM or behind a label,
        // so a fixed 6-instruction lookback walks straight out of the condition and into the
        // loop BODY, finds the body's INVOKEVIRTUAL, and calls a loop counter a predicate. A
        // LabelNode or FrameNode is a merge point, and no instruction before it contributed to
        // the operands on the stack here.
        AbstractInsnNode p = previousInBlock(insn);
        for (int i = 0; i < 8 && p != null; i++) {
            final int o = p.getOpcode();
            if (o == Opcodes.INVOKEVIRTUAL || o == Opcodes.INVOKESTATIC
                    || o == Opcodes.INVOKEINTERFACE || o == Opcodes.INVOKESPECIAL
                    || o == Opcodes.INVOKEDYNAMIC) {
                // A loop header is a call too. `while (it.hasNext())` is an IFEQ fed by an
                // INVOKEINTERFACE, and recording it means one arm record per iteration -- which
                // is the 5,825-invocation volume problem arriving by the front door. These two
                // names are loop headers BY CONSTRUCTION, so they are excluded by name at every
                // mode except `all`.
                if (p instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) p;
                    if (("hasNext".equals(mi.name) && "()Z".equals(mi.desc))
                            || ("hasMoreElements".equals(mi.name) && "()Z".equals(mi.desc))) {
                        return null;
                    }
                }
                return "call";
            }
            if (o == Opcodes.GETSTATIC) return "staticField";
            if (o == Opcodes.GETFIELD) return "field";
            if (o == Opcodes.ARRAYLENGTH) return null;      // a bounds check
            if (o == Opcodes.BALOAD) return null;           // a coverage probe
            p = previousInBlock(p);
        }
        return null;
    }

    // ---------------- executor call-site wrapping ----------------

    /**
     * Rewrites one submit call site. {@code INVOKESTATIC} inserted before the call so the task
     * on the stack is replaced by a context-carrying wrapper. No local, no branch, no frame, and
     * the net stack depth is unchanged.
     *
     * <p>For a 2-argument form the task is NOT on top (an {@code Executor} or a result object
     * is), so the two single-slot references are exchanged with {@code SWAP}, wrapped, and
     * exchanged back. Both operands are guaranteed one slot wide: a {@code long} or
     * {@code double} cannot appear there because every one of these signatures takes references.
     */
    private boolean emitWrap(MethodNode m, MethodInsnNode call) {
        final Submit s = submitOf(call);
        if (s == null) return false;
        InsnList l = new InsnList();
        if (s.argFromTop > 0) l.add(new InsnNode(Opcodes.SWAP));
        l.add(call(RUNTIME, s.wrapper, "(" + s.type + ")" + s.type));
        if (s.argFromTop > 0) l.add(new InsnNode(Opcodes.SWAP));
        m.instructions.insertBefore(call, l);
        return true;
    }

    private static final class Submit {
        final String wrapper;
        final String type;
        final int argFromTop;

        Submit(String wrapper, String type, int argFromTop) {
            this.wrapper = wrapper;
            this.type = type;
            this.argFromTop = argFromTop;
        }
    }

    private static final String RUNNABLE = "Ljava/lang/Runnable;";
    private static final String CALLABLE = "Ljava/util/concurrent/Callable;";
    private static final String COLLECTION = "Ljava/util/Collection;";
    private static final String SUPPLIER = "Ljava/util/function/Supplier;";
    private static final String FUNCTION = "Ljava/util/function/Function;";
    private static final String CONSUMER = "Ljava/util/function/Consumer;";
    private static final String BICONSUMER = "Ljava/util/function/BiConsumer;";

    /**
     * The wrap table. Matched by (name, descriptor) and NOT by owner, so a custom
     * {@code ExecutorService}, a Guava {@code ListeningExecutorService} and a Spring
     * {@code TaskExecutor} are all covered without naming any of them — the descriptor is the
     * contract. Taken from OpenTelemetry's executors instrumentation; see {@code Propagation}.
     */
    private static Submit submitOf(MethodInsnNode c) {
        final String n = c.name;
        final String d = c.desc;
        if ("execute".equals(n) && d.equals("(" + RUNNABLE + ")V")) {
            return new Submit("wrapRunnable", RUNNABLE, 0);
        }
        if ("submit".equals(n)) {
            if (d.startsWith("(" + RUNNABLE + ")")) return new Submit("wrapRunnable", RUNNABLE, 0);
            if (d.startsWith("(" + CALLABLE + ")")) return new Submit("wrapCallable", CALLABLE, 0);
            if (d.startsWith("(" + RUNNABLE + OBJ + ")")) {
                return new Submit("wrapRunnable", RUNNABLE, 1);
            }
        }
        if (("invokeAll".equals(n) || "invokeAny".equals(n))
                && d.startsWith("(" + COLLECTION + ")")) {
            return new Submit("wrapAll", COLLECTION, 0);
        }
        if ("runAsync".equals(n) && d.startsWith("(" + RUNNABLE + ")")) {
            return new Submit("wrapRunnable", RUNNABLE, 0);
        }
        if ("supplyAsync".equals(n) && d.startsWith("(" + SUPPLIER + ")")) {
            return new Submit("wrapSupplier", SUPPLIER, 0);
        }
        if (n.endsWith("Async")) {
            // thenRunAsync / thenApplyAsync / thenAcceptAsync / thenComposeAsync /
            // whenCompleteAsync / handleAsync, in both the 1-arg and the (fn, Executor) forms.
            if (d.startsWith("(" + RUNNABLE + ")")) return new Submit("wrapRunnable", RUNNABLE, 0);
            if (d.startsWith("(" + FUNCTION + ")")) return new Submit("wrapFunction", FUNCTION, 0);
            if (d.startsWith("(" + CONSUMER + ")")) return new Submit("wrapConsumer", CONSUMER, 0);
            if (d.startsWith("(" + BICONSUMER + ")")) {
                return new Submit("wrapBiConsumer", BICONSUMER, 0);
            }
            if (d.startsWith("(" + RUNNABLE + "Ljava/util/concurrent/Executor;)")) {
                return new Submit("wrapRunnable", RUNNABLE, 1);
            }
            if (d.startsWith("(" + FUNCTION + "Ljava/util/concurrent/Executor;)")) {
                return new Submit("wrapFunction", FUNCTION, 1);
            }
            if (d.startsWith("(" + CONSUMER + "Ljava/util/concurrent/Executor;)")) {
                return new Submit("wrapConsumer", CONSUMER, 1);
            }
        }
        if ("<init>".equals(n) && "java/lang/Thread".equals(c.owner)) {
            if (d.equals("(" + RUNNABLE + ")V")) return new Submit("wrapRunnable", RUNNABLE, 0);
            if (d.equals("(" + RUNNABLE + "Ljava/lang/String;)V")) {
                return new Submit("wrapRunnable", RUNNABLE, 1);
            }
        }
        return null;
    }

    /**
     * Locals for the throw handler's frame: receiver (if any), declared arguments, then
     * {@link Opcodes#TOP} for every remaining original slot. Structurally identical to
     * {@code ProbeEmitter.handlerFrameLocals} minus its timing slot, and for the same reasons:
     * declaring TOP where the verifier has a real type is a widening and always safe, and naming
     * the receiver plus the descriptor's arguments cannot be wrong because both come from the
     * method's own signature.
     *
     * <p>This emitter adds no local, so {@code m.maxLocals} is still the original, and a later
     * agent that pads frames to its own slot (ax-agent's {@code extendFrameLocals}) finds exactly
     * the slot count it expects.
     */
    /**
     * Does this method already carry an EXPANDED frame? Byte-identical in intent to
     * {@code ProbeEmitter.hasExpandedFrame}; kept here so the trace tier reads the method's own
     * state rather than trusting a flag threaded down from the installer.
     */
    private static boolean hasExpandedFrame(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null;
                insn = insn.getNext()) {
            if (insn instanceof FrameNode && ((FrameNode) insn).type == Opcodes.F_NEW) return true;
        }
        return false;
    }

    private static Object[] handlerFrameLocals(ClassNode cn, MethodNode m) {
        List<Object> locals = new ArrayList<Object>();
        int slot = 0;
        if ((m.access & Opcodes.ACC_STATIC) == 0) {
            locals.add(cn.name);
            slot = 1;
        }
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int i = 0; i < args.length; i++) {
            locals.add(frameType(args[i]));
            slot += args[i].getSize();
        }
        for (int s = slot; s < m.maxLocals; s++) locals.add(Opcodes.TOP);
        return locals.toArray();
    }

    private static Object frameType(Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return Opcodes.INTEGER;
            case Type.FLOAT:
                return Opcodes.FLOAT;
            case Type.LONG:
                return Opcodes.LONG;
            case Type.DOUBLE:
                return Opcodes.DOUBLE;
            case Type.ARRAY:
                return t.getDescriptor();
            default:
                return t.getInternalName();
        }
    }

    // ---------------- scanning helpers ----------------

    private static List<AbstractInsnNode> collectReturns(MethodNode m) {
        List<AbstractInsnNode> out = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            int op = i.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) out.add(i);
        }
        return out;
    }

    private static List<AbstractInsnNode> collectConditionals(MethodNode m) {
        List<AbstractInsnNode> out = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i instanceof JumpInsnNode) {
                int op = i.getOpcode();
                if (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) out.add(i);
                else if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) out.add(i);
            } else if (i instanceof TableSwitchInsnNode || i instanceof LookupSwitchInsnNode) {
                out.add(i);
            }
        }
        return out;
    }

    private static List<MethodInsnNode> collectSubmits(MethodNode m) {
        List<MethodInsnNode> out = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i instanceof MethodInsnNode && submitOf((MethodInsnNode) i) != null) {
                out.add((MethodInsnNode) i);
            }
        }
        return out;
    }

    /**
     * Does this method body hand work to {@code ForkJoinPool.commonPool()} via a parallel stream?
     * {@code Collection.parallelStream()}, or {@code .parallel()} on a
     * {@code java.util.stream} type.
     */
    static boolean usesParallelStream(MethodNode m) {
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (!(i instanceof MethodInsnNode)) continue;
            MethodInsnNode c = (MethodInsnNode) i;
            if ("parallelStream".equals(c.name)) return true;
            if ("parallel".equals(c.name) && c.owner.startsWith("java/util/stream/")) return true;
        }
        return false;
    }

    private static boolean hasJsr(MethodNode m) {
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            int op = i.getOpcode();
            if (op == Opcodes.JSR || op == Opcodes.RET) return true;
        }
        return false;
    }

    private static int firstLine(MethodNode m) {
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i instanceof LineNumberNode) return ((LineNumberNode) i).line;
        }
        return -1;
    }

    private static int lineOf(AbstractInsnNode insn) {
        for (AbstractInsnNode i = insn; i != null; i = i.getPrevious()) {
            if (i instanceof LineNumberNode) return ((LineNumberNode) i).line;
        }
        return -1;
    }

    /** The previous node that is an actual instruction (not a label, frame or line number). */
    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious();
        return p;
    }

    /**
     * The previous instruction WITHIN THE SAME BASIC BLOCK: line numbers are skipped, but a
     * {@code LabelNode} or {@code FrameNode} ends the walk. Both mark a point control can arrive
     * at from somewhere else, so nothing before one of them can have produced the operands
     * currently on the stack.
     */
    private static AbstractInsnNode previousInBlock(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p instanceof LineNumberNode) p = p.getPrevious();
        if (p == null) return null;
        if (p instanceof LabelNode || p instanceof FrameNode) return null;
        return p;
    }

    private static MethodInsnNode call(String owner, String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false);
    }

    /** Byte-identical to JaCoCo's {@code InstrSupport.push()} and ax-agent's copy of it. */
    static void push(InsnList l, int value) {
        if (value >= -1 && value <= 5) {
            l.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            l.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            l.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            l.add(new LdcInsnNode(Integer.valueOf(value)));
        }
    }

    private static List<AbstractInsnNode> emptyList() {
        return new ArrayList<AbstractInsnNode>(0);
    }

    private static List<MethodInsnNode> emptyMethodList() {
        return new ArrayList<MethodInsnNode>(0);
    }
}
