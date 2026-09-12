package io.auxin.agent.instrument;

import io.auxin.agent.config.Options;
import io.auxin.agent.health.Health;
import io.auxin.agent.manifest.Eligibility;
import io.auxin.agent.manifest.Manifest;
import io.auxin.agent.manifest.SchemaHash;
import io.auxin.agent.runtime.BootstrapBridge;
import io.auxin.agent.runtime.Tier2Registry;
import io.auxin.agent.util.Log;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Probe emission. This is the file the whole design is about.
 *
 * <h3>Tier-1 probe: read-then-store (G1, measured)</h3>
 * <pre>
 *   &lt;load probe array&gt;        // condy (class file &gt;= 55) or GETSTATIC $axProbes
 *   ASTORE  slot              // hoisted once per method, slot = old maxLocals
 *   ALOAD   slot
 *   &lt;push idx&gt;
 *   BALOAD
 *   IFNE    L_end
 *   ALOAD   slot
 *   &lt;push idx&gt;
 *   ICONST_1
 *   BASTORE
 *  L_end:                     // frame: SAME
 * </pre>
 * i.e. {@code if (!probes[idx]) probes[idx] = true;}.
 *
 * <p><b>Why not the blind store PLAN-v2 chose.</b> G1 measured both at 10 threads: blind
 * 18.14 ns/op vs read-then-store 10.70 ns/op, +0.72-0.77 ns per probe vs ~0. A blind store takes
 * the probe array's cache line Exclusive on <i>every single execution, for ever</i>; the branch
 * costs one extra stack map frame once, at class load. The no-frame argument was real and is an
 * order of magnitude smaller than the contention it was traded against. The blind emitter is
 * still here, behind {@code ax.probe.mode=blind}, so the decision stays reversible.
 *
 * <p><b>The frame.</b> The probe sits at offset 0, before any other frame, so the merge point is
 * a {@code SAME} frame: identical locals, empty stack, no types named — which is also why it is
 * safe inside {@code <init>}, where the implicit frame's local 0 is {@code uninitializedThis} and
 * naming a type would be a VerifyError. It is emitted by hand with
 * {@code visitFrame(F_SAME, 0, null, 0, null)}; {@code ClassWriter(0)} still suffices and
 * COMPUTE_FRAMES (which loads classes, causing ClassCircularityError and startup CPU burn) is
 * still never used. In a class read with EXPAND_FRAMES (any class carrying a tier-2 method) the
 * frame must be expanded too — ASM cannot mix F_NEW and compressed frames in one method — so
 * there an explicit F_NEW entry frame is written instead.
 *
 * <p><b>Class file version.</b> &gt;= 51: frame emitted. &lt; 50: no StackMapTable exists, the
 * inference verifier handles the branch, no frame emitted. Exactly 50: frames are optional there
 * and adding one to a method that has none forces HotSpot's type-checker to fail and fail over to
 * the inference verifier per class — so v50 keeps the blind store. Documented, not silent:
 * the mode is reported per class at debug level.
 *
 * <h3>Probe array access</h3>
 * <ul>
 *   <li><b>condy</b> (class file &gt;= 55, loader can see the agent): one {@code ConstantDynamic},
 *       no field, no {@code <clinit>}, strippable by Tier-1b.</li>
 *   <li><b>condy + self-BSM via {@code java.lang.$Auxin}</b> (class file &gt;= 55, loader
 *       cannot see the agent jar): the same {@code ConstantDynamic}, but its bootstrap method is
 *       a synthetic {@code private static $axInit} on the instrumented class <i>itself</i>, which
 *       does the {@code java.lang} dance internally. This is JaCoCo's {@code $jacocoInit} shape
 *       and it is the default ({@code ax.bridge.shape=selfbsm}). See {@link #bridgeBootstrap}.</li>
 *   <li><b>field + {@code <clinit>} via ProbeHolder</b> (class file &lt; 55): condy does not
 *       exist below 55. Adds a field, so it is installer-only and never de-instrumented.</li>
 *   <li><b>field + {@code <clinit>} via {@code java.lang.$Auxin}</b> (class file &lt; 55
 *       and agent-invisible, or {@code ax.bridge.shape=field}): the pre-F2 shape, kept as a
 *       rollback. See {@link io.auxin.agent.runtime.BootstrapBridge}.</li>
 * </ul>
 *
 * <h3>Why the self-BSM shape exists (isolation suite F2)</h3>
 * A field + {@code <clinit>} bridge silently gives up three things, all measured:
 * <ol>
 *   <li><b>Interfaces cannot be instrumented at all</b> — an interface cannot hold a mutable
 *       static field, so every {@code default} and {@code static} interface method in an OSGi
 *       bundle / JPMS layer / child-first loader was skipped ({@code interfaceNeedsField}).
 *       A {@code private static} interface method is legal from class file <b>53</b> and condy
 *       needs <b>55</b>, so wherever condy is available the self-BSM shape is legal too.</li>
 *   <li><b>Tier-1b can never de-instrument a bridged class</b> —
 *       {@code DrainThread.stripCoveredClasses()} needs a {@link Class} handle and only condy
 *       supplies one ({@code lookup.lookupClass()}). Probes stayed on the hot path for the life
 *       of the JVM in exactly the containers the bridge exists for.</li>
 *   <li><b>F3 cannot be defended</b> — an {@code INSTANCEOF} branch cannot be added to somebody
 *       else's {@code <clinit>} without inserting a merge frame. In a method we author from
 *       scratch there is nothing to merge with, so the guard is cheap and exact.</li>
 * </ol>
 * What does <b>not</b> change: tier 2 is still not bridgeable. Any {@code equals}-based hop
 * allocates an {@code Object[]} per invocation, which is not payable on a hot path, so
 * {@code tier2NotBridgeable} stays.
 *
 * <h3>Index assignment</h3>
 * Always a lookup in the build-time manifest, NEVER visit order (A14 defect 2).
 */
public final class ProbeEmitter {

    public static final String PROBE_NAME = "$axProbes";
    public static final String PROBE_DESC = "[Z";

    static final String HOLDER = "io/auxin/agent/runtime/ProbeHolder";
    static final String BSM_NAME = "bootstrap";
    static final String BSM_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;I)[Z";
    static final String GET_DESC = "(Ljava/lang/String;I)[Z";

    private static final String OBJECT = "java/lang/Object";
    private static final String OBJECT_DESC = "Ljava/lang/Object;";
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";

    /**
     * The synthetic condy bootstrap method the self-BSM bridge shape adds to the instrumented
     * class. Same role as JaCoCo's {@code $jacocoInit}. {@code $} keeps it out of the way of
     * anything a Java compiler can emit.
     */
    public static final String BRIDGE_BSM_NAME = "$axInit";
    /**
     * {@code (MethodHandles$Lookup, String, Class) -> Object}: the bare condy bootstrap
     * signature, with no static arguments — the class name and probe count are baked into the
     * method body as constants, so the constant pool names nothing but {@code java.lang},
     * {@code java.lang.invoke} and the class itself.
     */
    public static final String BRIDGE_BSM_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
                    + OBJECT_DESC;

    static final String TIER2 = "io/auxin/agent/runtime/Tier2Runtime";
    static final String TIER2_ENTER = "enter";
    static final String TIER2_ENTER_DESC = "()J";
    static final String TIER2_EXIT = "exit";
    static final String TIER2_EXIT_DESC = "(JI)V";
    static final String TIER2_EXIT_ERROR = "exitError";
    static final String TIER2_EXIT_ERROR_DESC = "(JILjava/lang/Throwable;)V";

    /** Condy is only available from class file version 55 (Java 11). */
    public static final int CONDY_MIN_VERSION = 55;
    /** StackMapTable is only meaningful from version 50 (Java 6). */
    private static final int FRAMES_MIN_VERSION = 50;
    /**
     * Version 50 is the one version where a StackMapTable exists but is optional and HotSpot
     * fails over to the inference verifier. Adding a frame there buys a per-class failover;
     * the blind store avoids the question entirely.
     */
    private static final int READ_THEN_STORE_MIN_VERSION = 51;

    /** condy: no field, no {@code <clinit>}, de-instrumentable. */
    static final int ACCESS_CONDY = 0;
    /** static field initialised from {@link io.auxin.agent.runtime.ProbeHolder}. */
    static final int ACCESS_FIELD_HOLDER = 1;
    /** static field initialised through {@code java.lang.$Auxin}: java.lang refs only. */
    static final int ACCESS_FIELD_BRIDGE = 2;
    /**
     * F2, and the default for an agent-invisible loader at class file &gt;= 55: condy whose
     * bootstrap method is a synthetic {@code $axInit} on the instrumented class itself, which
     * reads {@code java.lang.$Auxin}. java.lang refs only, no field, interfaces work,
     * de-instrumentable.
     */
    static final int ACCESS_CONDY_BRIDGE = 3;

    public static final class Result {
        public int probes;
        public int tier2Methods;
        public boolean usedCondy;
        public int access;
        public String skipReason;

        public boolean changed() { return probes > 0 || tier2Methods > 0; }
    }

    private final boolean tier1Enabled;
    private final boolean tier2Enabled;
    private final boolean blindMode;
    private final boolean condyArrayDescriptor;
    private final boolean selfBsmBridge;

    public ProbeEmitter(Options options) {
        this.tier1Enabled = options.tier1Enabled;
        this.tier2Enabled = options.tier2Enabled;
        this.blindMode = Options.PROBE_MODE_BLIND.equals(options.probeMode);
        this.condyArrayDescriptor = options.condyArrayDescriptor;
        this.selfBsmBridge = Options.BRIDGE_SHAPE_SELF_BSM.equals(options.bridgeShape);
    }

    /**
     * Mutates {@code cn} in place.
     *
     * @param agentVisible can code loaded by this class's loader resolve {@code ProbeHolder}?
     *                     When false the {@code java.lang} bridge is the only legal way to reach
     *                     the probe array; see {@link LoaderVisibility}.
     * @return what was done, or a Result carrying a skipReason and no changes.
     */
    public Result instrument(ClassNode cn, Manifest.ClassEntry entry, boolean agentVisible) {
        Result r = new Result();

        List<MethodNode> eligible = new ArrayList<MethodNode>();
        List<String[]> schemaKeys = new ArrayList<String[]>();
        for (int i = 0; i < cn.methods.size(); i++) {
            MethodNode m = cn.methods.get(i);
            if (!Eligibility.isProbeEligible(m.access, m.name)) continue;
            eligible.add(m);
            schemaKeys.add(new String[]{m.name, m.desc});
        }
        if (eligible.isEmpty()) {
            r.skipReason = Health.SKIP_NO_ELIGIBLE_METHODS;
            return r;
        }

        // CONTRACTS section 1: refuse to probe a class whose computed hash differs.
        if (entry.schemaHash != null && entry.schemaHash.length() > 0) {
            String computed = SchemaHash.compute(schemaKeys);
            if (!computed.equals(entry.schemaHash)) {
                r.skipReason = Health.SKIP_SCHEMA_HASH_MISMATCH;
                return r;
            }
        }

        final int version = cn.version & 0xFFFF;
        final boolean isInterface = (cn.access & Opcodes.ACC_INTERFACE) != 0;

        // Which mechanism delivers the probe array to this class?
        final int access;
        if (!agentVisible) {
            // The loader cannot resolve ProbeHolder. Emitting a reference to it anyway would
            // throw NoClassDefFoundError inside application code.
            if (!BootstrapBridge.installed()) {
                r.skipReason = Health.SKIP_AGENT_NOT_VISIBLE;
                return r;
            }
            // F3: re-checked here as well as in pass 1, because the two reads straddle the
            // ASM parse and the field is writable by anything in the JVM at any moment. With the
            // self-BSM shape a tamper landing AFTER this check is also survivable (see
            // bridgeBootstrap), so this is now the outer of two defences, not the only one.
            if (!BootstrapBridge.intact()) {
                r.skipReason = Health.SKIP_BRIDGE_TAMPERED;
                return r;
            }
            // F2: keep condy wherever condy exists. Below 55 there is no condy at all, so the
            // field prologue remains the only mechanism there.
            access = selfBsmBridge && version >= CONDY_MIN_VERSION
                    ? ACCESS_CONDY_BRIDGE : ACCESS_FIELD_BRIDGE;
        } else if (version >= CONDY_MIN_VERSION) {
            access = ACCESS_CONDY;
        } else {
            access = ACCESS_FIELD_HOLDER;
        }
        if (needsField(access) && isInterface) {
            // an interface cannot hold a mutable static field, and the field is the only way in
            r.skipReason = access == ACCESS_FIELD_BRIDGE
                    ? Health.SKIP_INTERFACE_NEEDS_FIELD : Health.SKIP_LEGACY_INTERFACE;
            return r;
        }
        r.access = access;
        r.usedCondy = !needsField(access);

        final String dotted = cn.name.replace('/', '.');
        ConstantDynamic constant = null;
        // Built BEFORE any instruction is emitted. A condy whose bootstrap method we failed to
        // add would be a NoSuchMethodError inside application code, so there must be no path on
        // which the probes go in and the method does not.
        MethodNode bridgeBsm = null;
        if (access == ACCESS_CONDY) {
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, HOLDER, BSM_NAME, BSM_DESC, false);
            // G3: on JDK 17 the constant can be declared [Z directly and the CHECKCAST dropped,
            // saving 3 bytes/probe. JDK-8216970 broke exactly that on Java 11, which we have not
            // tested, so the declared type stays Ljava/lang/Object; + CHECKCAST [Z unless
            // ax.condy.descriptor=array says otherwise.
            constant = new ConstantDynamic(PROBE_NAME,
                    condyArrayDescriptor ? PROBE_DESC : OBJECT_DESC, bsm,
                    new Object[]{dotted, Integer.valueOf(entry.probeCount)});
        } else if (access == ACCESS_CONDY_BRIDGE) {
            if (hasMethod(cn, BRIDGE_BSM_NAME, BRIDGE_BSM_DESC)) {
                // Either this class is already instrumented or it genuinely declares the name.
                // Retargeting an existing method as a bootstrap method is not survivable.
                r.skipReason = Health.SKIP_BRIDGE_BSM_UNAVAILABLE;
                return r;
            }
            // REF_invokeStatic on the class's OWN name: the constant pool still names nothing
            // from io/auxin. isInterface must be true for an interface, or the CP entry is
            // a Methodref where the JVM requires an InterfaceMethodref.
            Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, cn.name,
                    BRIDGE_BSM_NAME, BRIDGE_BSM_DESC, isInterface);
            // Always Ljava/lang/Object; + CHECKCAST [Z here, whatever ax.condy.descriptor says:
            // this is JaCoCo's exact, field-proven shape, and the bootstrap method's declared
            // return type is Object, so declaring the constant [Z would add an asType conversion
            // to the one path that exists precisely because it must not surprise anyone.
            constant = new ConstantDynamic(PROBE_NAME, OBJECT_DESC, bsm);
            bridgeBsm = bridgeBootstrap(cn, dotted, entry.probeCount, version, isInterface);
        }

        int notInManifest = 0;
        int notObservable = 0;
        int frameUnsupported = 0;
        int tier2NotBridgeable = 0;

        for (int i = 0; i < eligible.size(); i++) {
            MethodNode m = eligible.get(i);
            Manifest.MethodEntry me = entry.method(m.name, m.desc);
            if (me == null || me.idx < 0) {
                notInManifest++;
                continue;
            }
            // C51: a probe on a method that cannot be covered dynamically is cost without
            // signal, and a never-flipping probe would be reported as dead code.
            if (me.notDynamicallyObservable()) {
                notObservable++;
                continue;
            }

            if (tier2Enabled && me.tier2) {
                // Tier-2 calls Tier2Runtime directly — there is no Object.equals trick for a
                // hot-path call, and a java.lang bridge cannot carry one: ANY equals-based hop
                // allocates an Object[] per invocation, which is not payable on a hot path, and
                // condy is a one-shot constant, not a per-call dispatch. So a class whose loader
                // cannot see the agent gets tier-1 only, on BOTH bridge shapes; the alternative
                // is a NoClassDefFoundError on a boundary method.
                if (isBridge(access)) {
                    tier2NotBridgeable++;
                } else if (emitTier2(cn, m, dotted, me, version)) {
                    r.tier2Methods++;
                }
            }
            if (tier1Enabled) {
                if (emitTier1(cn, m, constant, access, me.idx, version)) r.probes++;
                else frameUnsupported++;
            }
        }

        if (notInManifest > 0) Health.skip(Health.SKIP_METHOD_NOT_IN_MANIFEST, notInManifest);
        if (notObservable > 0) Health.skip(Health.SKIP_NOT_DYNAMICALLY_OBSERVABLE, notObservable);
        if (frameUnsupported > 0) {
            Health.skip(Health.SKIP_FRAME_EMISSION_UNSUPPORTED, frameUnsupported);
        }
        if (tier2NotBridgeable > 0) {
            Health.skip(Health.SKIP_TIER2_NOT_BRIDGEABLE, tier2NotBridgeable);
        }

        if (r.probes > 0) {
            if (bridgeBsm != null) {
                // Adding a method at INITIAL class load is legal; the no-schema-change rule
                // binds redefine/retransform only (E2 fact 2). ProbeStripper must therefore
                // never remove it again — see its javadoc.
                cn.methods.add(bridgeBsm);
            } else if (needsField(access)) {
                addFieldAndClinit(cn, dotted, entry.probeCount, access);
            }
        }
        return r;
    }

    /** Does this delivery mechanism need a mutable static field on the instrumented class? */
    private static boolean needsField(int access) {
        return access == ACCESS_FIELD_HOLDER || access == ACCESS_FIELD_BRIDGE;
    }

    /** Does the probe array arrive through {@code java.lang.$Auxin} rather than directly? */
    private static boolean isBridge(int access) {
        return access == ACCESS_FIELD_BRIDGE || access == ACCESS_CONDY_BRIDGE;
    }

    private static boolean hasMethod(ClassNode cn, String name, String desc) {
        for (int i = 0; i < cn.methods.size(); i++) {
            MethodNode m = cn.methods.get(i);
            if (name.equals(m.name) && desc.equals(m.desc)) return true;
        }
        return false;
    }

    // ---------------- tier 1 ----------------

    /**
     * Inserts one probe at the head of {@code m}.
     *
     * <p>Fail-open at METHOD granularity: anything that would make the frame unsafe skips this
     * one method and leaves the rest of the class instrumented. Returning false is always
     * better than handing the JVM a class it will reject — a VerifyError is an outage.
     *
     * @return true when a probe was installed.
     */
    private boolean emitTier1(ClassNode cn, MethodNode m, ConstantDynamic constant,
                              int access, int idx, int version) {
        try {
            // Class file 50 keeps the blind store: a StackMapTable is optional there, so adding
            // one frame to a method that has none makes the type-checker fail and HotSpot fail
            // over to the inference verifier, per class. Below 50 there is no StackMapTable at
            // all and the branch needs no frame; from 51 the frame is emitted.
            final boolean frameOptionalVersion = version >= FRAMES_MIN_VERSION
                    && version < READ_THEN_STORE_MIN_VERSION;
            if (blindMode || frameOptionalVersion) {
                m.instructions.insert(blindProbe(constant, access, cn.name, idx));
                m.maxStack = Math.max(m.maxStack, 3);
                return true;
            }
            // one free slot beyond every existing local; long/double widths are already
            // accounted for by maxLocals, so maxLocals itself is always free.
            final int slot = m.maxLocals;
            if (slot >= 65535) return false;

            // A frame already at offset 0 (a method whose first instruction is a branch target,
            // e.g. `while (true)`) MUST be reused: two entries at one offset is an illegal
            // StackMapTable, and ASM would silently drop ours or throw.
            LabelNode existing = version >= FRAMES_MIN_VERSION ? entryFrameLabel(m) : null;
            LabelNode end = existing != null ? existing : new LabelNode();

            InsnList l = loadArray(constant, access, cn.name);
            l.add(new VarInsnNode(Opcodes.ASTORE, slot));
            l.add(new VarInsnNode(Opcodes.ALOAD, slot));
            push(l, idx);
            l.add(new InsnNode(Opcodes.BALOAD));
            l.add(new JumpInsnNode(Opcodes.IFNE, end));
            l.add(new VarInsnNode(Opcodes.ALOAD, slot));
            push(l, idx);
            l.add(new InsnNode(Opcodes.ICONST_1));
            l.add(new InsnNode(Opcodes.BASTORE));
            if (existing == null) {
                l.add(end);
                if (version >= FRAMES_MIN_VERSION) l.add(entryFrame(cn, m));
            }
            m.instructions.insert(l);

            m.maxLocals = slot + 1;
            m.maxStack = Math.max(m.maxStack, 3);
            return true;
        } catch (Throwable t) {
            Log.debug("tier-1 probe emission failed for " + cn.name + "#" + m.name, t);
            return false;
        }
    }

    /**
     * Leaves the probe array on the stack. Identical for both condy shapes — the difference
     * between them lives entirely in the {@code BootstrapMethods} entry, so there is zero added
     * cost on the normal agent-visible path.
     */
    private InsnList loadArray(ConstantDynamic constant, int access, String owner) {
        InsnList l = new InsnList();
        if (constant != null) {
            l.add(new LdcInsnNode(constant));
            // The CHECKCAST exists only when the constant is declared Ljava/lang/Object;.
            if (!PROBE_DESC.equals(constant.getDescriptor())) {
                l.add(new TypeInsnNode(Opcodes.CHECKCAST, PROBE_DESC));
            }
        } else {
            l.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, PROBE_NAME, PROBE_DESC));
        }
        return l;
    }

    /** PLAN-v2's original shape, kept reachable by {@code ax.probe.mode=blind} (G1 reversed it). */
    private InsnList blindProbe(ConstantDynamic constant, int access, String owner, int idx) {
        InsnList l = loadArray(constant, access, owner);
        push(l, idx);
        l.add(new InsnNode(Opcodes.ICONST_1));
        l.add(new InsnNode(Opcodes.BASTORE));
        return l;
    }

    /**
     * The merge point's frame. SAME when the method's frames are compressed (identical locals,
     * empty stack, no types named — safe in {@code <init>}, where local 0 is
     * {@code uninitializedThis}); expanded when the class was read with EXPAND_FRAMES, because
     * ASM cannot mix F_NEW and compressed frames within one method.
     */
    private static FrameNode entryFrame(ClassNode cn, MethodNode m) {
        if (!hasExpandedFrame(m)) return new FrameNode(Opcodes.F_SAME, 0, null, 0, null);
        List<Object> locals = new ArrayList<Object>();
        if ((m.access & Opcodes.ACC_STATIC) == 0) {
            // before the super() call this is uninitializedThis, and saying so is the ONLY
            // correct answer: naming cn.name here is the classic <init> VerifyError.
            locals.add("<init>".equals(m.name) ? Opcodes.UNINITIALIZED_THIS : (Object) cn.name);
        }
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int i = 0; i < args.length; i++) locals.add(frameType(args[i]));
        return new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]);
    }

    private static boolean hasExpandedFrame(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode && ((FrameNode) insn).type == Opcodes.F_NEW) return true;
        }
        return false;
    }

    /**
     * @return the label of a stack map frame that already sits at bytecode offset 0, or null.
     * ASM writes labels and frames in list order, so a label inserted immediately before the
     * frame resolves to exactly that offset.
     */
    private static LabelNode entryFrameLabel(MethodNode m) {
        LabelNode label = null;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                label = (LabelNode) insn;
            } else if (insn instanceof FrameNode) {
                if (label != null) return label;
                LabelNode fresh = new LabelNode();
                m.instructions.insertBefore(insn, fresh);
                return fresh;
            } else if (!(insn instanceof LineNumberNode)) {
                return null;              // a real instruction: nothing is at offset 0
            }
        }
        return null;
    }

    private static void addFieldAndClinit(ClassNode cn, String dotted, int probeCount, int access) {
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_TRANSIENT,
                PROBE_NAME, PROBE_DESC, null, null));

        MethodNode clinit = null;
        for (int i = 0; i < cn.methods.size(); i++) {
            if ("<clinit>".equals(cn.methods.get(i).name)) {
                clinit = cn.methods.get(i);
                break;
            }
        }
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            clinit.maxStack = 0;
            clinit.maxLocals = 0;
            cn.methods.add(clinit);
        }
        InsnList init = access == ACCESS_FIELD_BRIDGE
                ? bridgePrologue(cn, dotted, probeCount)
                : holderPrologue(cn, dotted, probeCount);
        clinit.instructions.insert(init);
        clinit.maxStack = Math.max(clinit.maxStack, access == ACCESS_FIELD_BRIDGE ? 5 : 2);
    }

    /** {@code $axProbes = ProbeHolder.get("cls", n);} — one agent reference, one call. */
    private static InsnList holderPrologue(ClassNode cn, String dotted, int probeCount) {
        InsnList init = new InsnList();
        init.add(new LdcInsnNode(dotted));
        push(init, probeCount);
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOLDER, "get", GET_DESC, false));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, PROBE_NAME, PROBE_DESC));
        return init;
    }

    /**
     * JaCoCo's bridge prologue: {@code Object[] a = {"cls", n}; $Auxin.data.equals(a);
     * $axProbes = (boolean[]) a[0];}
     *
     * <p>The overridden {@code equals} replaces {@code a[0]} with the probe array. The only types
     * this leaves in the instrumented class's constant pool are {@code java.lang.$Auxin},
     * {@code java.lang.Object}, {@code java.lang.Integer} and {@code [Z} — all boot-delegated by
     * every class loader arrangement in existence, which is the entire point.
     */
    private static InsnList bridgePrologue(ClassNode cn, String dotted, int probeCount) {
        InsnList l = new InsnList();
        l.add(new FieldInsnNode(Opcodes.GETSTATIC, BootstrapBridge.BRIDGE_INTERNAL,
                BootstrapBridge.DATA_NAME, BootstrapBridge.DATA_DESC));
        push(l, 2);
        l.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new InsnNode(Opcodes.ICONST_0));
        l.add(new LdcInsnNode(dotted));
        l.add(new InsnNode(Opcodes.AASTORE));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new InsnNode(Opcodes.ICONST_1));
        push(l, probeCount);
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false));
        l.add(new InsnNode(Opcodes.AASTORE));
        l.add(new InsnNode(Opcodes.DUP_X1));     // args below the receiver, to survive the call
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals",
                "(Ljava/lang/Object;)Z", false));
        l.add(new InsnNode(Opcodes.POP));
        l.add(new InsnNode(Opcodes.ICONST_0));
        l.add(new InsnNode(Opcodes.AALOAD));
        l.add(new TypeInsnNode(Opcodes.CHECKCAST, PROBE_DESC));
        l.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, PROBE_NAME, PROBE_DESC));
        return l;
    }

    /**
     * F2 — the synthetic condy bootstrap method, on the instrumented class itself.
     *
     * <pre>
     *   private static synthetic Object $axInit(MethodHandles.Lookup l, String n, Class t) {
     *       Object[] a = { "cls", Integer.valueOf(probeCount), l.lookupClass() };
     *       java.lang.$Auxin.data.equals(a);     // fills a[0] with the probe array
     *       Object p = a[0];
     *       return p instanceof boolean[] ? p : new boolean[probeCount];
     *   }
     * </pre>
     *
     * <p><b>Why every type here is deliberate.</b> The only types named are
     * {@code java.lang.$Auxin}, {@code java.lang.Object}, {@code java.lang.Integer},
     * {@code java.lang.invoke.MethodHandles$Lookup}, {@code java.lang.Class}, {@code [Z} and the
     * class itself. Every one of them is in {@code java.base} and under a {@code java.*} package,
     * which the OSGi core spec, JBoss Modules and every child-first loader in existence must
     * delegate to the parent, and which java.base exports unconditionally so no JPMS read edge,
     * export or open is needed. {@code javap -v -p -c | grep -c io/auxin} is 0.
     *
     * <p><b>The strip handle.</b> {@code a[2] = lookup.lookupClass()} is the entire reason Tier-1b
     * works on a bridged class: {@code BootstrapBridge.Data.equals} runs in the agent's loader and
     * hands that {@link Class} to {@code ProbeHolder.registerLoadedClass}, which is what
     * {@code DrainThread.stripCoveredClasses()} retransforms.
     *
     * <p><b>The F3 guard.</b> {@code java.lang.$Auxin.data} is a public static field, so a
     * third party can replace it with an object whose {@code equals} does not fill in
     * {@code a[0]}. The {@code INSTANCEOF [Z} branch turns that from
     * {@code ClassCastException} inside application code into a throwaway array that nothing ever
     * reads — the class simply records no coverage. This is only affordable here: there are no
     * pre-existing stack map frames in a method we author, so the merge point is one
     * {@code SAME_LOCALS_1_STACK_ITEM} entry written by hand. COMPUTE_FRAMES stays banned.
     *
     * <p><b>Interfaces.</b> {@code ACC_PRIVATE | ACC_STATIC} on an interface method is legal from
     * class file 52 (JVMS 4.6: at 52.0+ each interface method must have exactly one of
     * ACC_PUBLIC / ACC_PRIVATE) and condy needs 55, so wherever this shape is chosen it is legal.
     * The bootstrap method handle is resolved against the class itself, which is why a private
     * method is reachable at all.
     */
    private static MethodNode bridgeBootstrap(ClassNode cn, String dotted, int probeCount,
                                              int version, boolean isInterface) {
        MethodNode m = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                BRIDGE_BSM_NAME, BRIDGE_BSM_DESC, null, null);
        InsnList l = m.instructions;

        // java.lang.$Auxin.data
        l.add(new FieldInsnNode(Opcodes.GETSTATIC, BootstrapBridge.BRIDGE_INTERNAL,
                BootstrapBridge.DATA_NAME, BootstrapBridge.DATA_DESC));
        // new Object[3]
        push(l, 3);
        l.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
        // a[0] = "cls"
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new InsnNode(Opcodes.ICONST_0));
        l.add(new LdcInsnNode(dotted));
        l.add(new InsnNode(Opcodes.AASTORE));
        // a[1] = Integer.valueOf(probeCount)
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new InsnNode(Opcodes.ICONST_1));
        push(l, probeCount);
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                "(I)Ljava/lang/Integer;", false));
        l.add(new InsnNode(Opcodes.AASTORE));
        // a[2] = lookup.lookupClass()   -- the Tier-1b strip handle
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new InsnNode(Opcodes.ICONST_2));
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass",
                "()Ljava/lang/Class;", false));
        l.add(new InsnNode(Opcodes.AASTORE));
        // data.equals(a)   -- DUP_X1 keeps the array below the receiver, to survive the call
        l.add(new InsnNode(Opcodes.DUP_X1));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OBJECT, "equals",
                "(Ljava/lang/Object;)Z", false));
        l.add(new InsnNode(Opcodes.POP));
        l.add(new InsnNode(Opcodes.ICONST_0));
        l.add(new InsnNode(Opcodes.AALOAD));
        // F3: return it only if the handler really replaced it with a boolean[].
        LabelNode tampered = new LabelNode();
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new TypeInsnNode(Opcodes.INSTANCEOF, PROBE_DESC));
        l.add(new JumpInsnNode(Opcodes.IFEQ, tampered));
        l.add(new InsnNode(Opcodes.ARETURN));
        l.add(tampered);
        if (version >= FRAMES_MIN_VERSION) {
            // Locals are exactly the (static) method's three declared parameters and are
            // unchanged on both paths; the stack carries the one Object we DUPed. Nothing is
            // named that could be wrong: SAME_LOCALS_1_STACK_ITEM with java/lang/Object.
            l.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{OBJECT}));
        }
        l.add(new InsnNode(Opcodes.POP));
        push(l, probeCount);
        l.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN));
        l.add(new InsnNode(Opcodes.ARETURN));

        m.maxStack = 6;     // measured maximum is 5, during the array element stores
        m.maxLocals = 3;    // static (Lookup, String, Class)
        if (isInterface) {
            Log.debug("self-BSM bridge on INTERFACE " + cn.name
                    + " (private static interface method, class file " + version + ")");
        }
        return m;
    }

    // ---------------- tier 2 ----------------

    /**
     * Wraps the body in try/finally:
     * <pre>
     *   long t = Tier2Runtime.enter();            // 0 when this call is not being timed
     *   try { ...body... Tier2Runtime.exit(t, id) at every return }
     *   catch (Throwable e) { Tier2Runtime.exitError(t, id, e); throw e; }
     * </pre>
     * The handler is the only construct in the whole agent that needs a stack map frame, and it
     * is one F_FULL frame per instrumented method (not per probe site), emitted by hand so that
     * {@code ClassWriter(0)} still suffices.
     */
    private boolean emitTier2(ClassNode cn, MethodNode m, String dotted,
                              Manifest.MethodEntry me, int version) {
        if ("<init>".equals(m.name)) return false;      // uninitialisedThis frames: not worth it
        if (hasJsr(m)) return false;                    // pre-Java-6 subroutines
        // Check BEFORE mutating anything: every existing frame must be expanded (F_NEW) or we
        // cannot teach it about the timing local, and a half-instrumented method is a VerifyError.
        if (version >= FRAMES_MIN_VERSION && !framesAreExpanded(m)) return false;

        int methodId = Tier2Registry.register(dotted, me.idx, m.name, m.desc);
        if (methodId < 0) return false;

        final int startSlot = m.maxLocals;
        final int exSlot = startSlot + 2;

        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();

        InsnList pre = new InsnList();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TIER2, TIER2_ENTER, TIER2_ENTER_DESC, false));
        pre.add(new VarInsnNode(Opcodes.LSTORE, startSlot));
        pre.add(tryStart);
        m.instructions.insert(pre);

        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                InsnList exit = new InsnList();
                exit.add(new VarInsnNode(Opcodes.LLOAD, startSlot));
                push(exit, methodId);
                exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TIER2, TIER2_EXIT, TIER2_EXIT_DESC, false));
                m.instructions.insertBefore(insn, exit);
            }
        }

        InsnList post = new InsnList();
        post.add(tryEnd);
        post.add(handler);
        FrameNode handlerFrame = null;
        if (version >= FRAMES_MIN_VERSION) {
            Object[] locals = handlerFrameLocals(cn, m, startSlot);
            // F_NEW (expanded): the class was read with EXPAND_FRAMES, so every frame in the
            // method is expanded and they must stay consistent. ASM compresses them on write.
            handlerFrame = new FrameNode(Opcodes.F_NEW, locals.length, locals,
                    1, new Object[]{"java/lang/Throwable"});
            post.add(handlerFrame);
        }
        post.add(new VarInsnNode(Opcodes.ASTORE, exSlot));
        post.add(new VarInsnNode(Opcodes.LLOAD, startSlot));
        push(post, methodId);
        post.add(new VarInsnNode(Opcodes.ALOAD, exSlot));
        post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TIER2, TIER2_EXIT_ERROR, TIER2_EXIT_ERROR_DESC, false));
        post.add(new VarInsnNode(Opcodes.ALOAD, exSlot));
        post.add(new InsnNode(Opcodes.ATHROW));
        m.instructions.add(post);

        if (m.tryCatchBlocks == null) m.tryCatchBlocks = new ArrayList<TryCatchBlockNode>();
        m.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, null));

        // Teach every pre-existing frame about the timing local. Without this the verifier sees
        // TOP at that slot inside the protected range and refuses to merge it with the handler
        // frame's LONG ("Type top ... is not assignable to long"). The local is definitely
        // assigned on every path in, because the store happens before the range begins.
        if (handlerFrame != null) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof FrameNode) || insn == handlerFrame) continue;
                extendFrameLocals((FrameNode) insn, startSlot);
            }
        }

        m.maxLocals = exSlot + 1;
        m.maxStack = Math.max(m.maxStack + 3, 6);
        return true;
    }

    /**
     * Locals for the handler frame: receiver (if any), declared arguments, TOP padding for every
     * remaining original slot, then our LONG. Declaring TOP where the verifier has a real type
     * is always safe (it is a widening); declaring LONG for our slot is safe because the store
     * happens before the protected range begins, on every path.
     */
    private static Object[] handlerFrameLocals(ClassNode cn, MethodNode m, int startSlot) {
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
        for (int s = slot; s < startSlot; s++) locals.add(Opcodes.TOP);
        locals.add(Opcodes.LONG);
        return locals.toArray();
    }

    private static boolean framesAreExpanded(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode && ((FrameNode) insn).type != Opcodes.F_NEW) return false;
        }
        return true;
    }

    /** Appends TOP padding plus LONG to one expanded frame so it declares the timing local. */
    private static void extendFrameLocals(FrameNode f, int startSlot) {
        List<Object> locals = f.local == null ? new ArrayList<Object>() : f.local;
        int slots = 0;
        for (int i = 0; i < locals.size(); i++) {
            Object o = locals.get(i);
            slots += (o == Opcodes.LONG || o == Opcodes.DOUBLE) ? 2 : 1;
        }
        if (slots > startSlot) return;        // cannot happen: startSlot is the original maxLocals
        List<Object> extended = new ArrayList<Object>(locals);
        for (int s = slots; s < startSlot; s++) extended.add(Opcodes.TOP);
        extended.add(Opcodes.LONG);
        f.local = extended;
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

    private static boolean hasJsr(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op == Opcodes.JSR || op == Opcodes.RET) return true;
        }
        return false;
    }

    // ---------------- shared ----------------

    /** Byte-identical to JaCoCo's InstrSupport.push(). */
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

    /** True when the instruction is one of the forms {@link #push} can emit. */
    static boolean isPush(AbstractInsnNode insn) {
        if (insn == null) return false;
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return true;
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return true;
        return op == Opcodes.LDC && ((LdcInsnNode) insn).cst instanceof Integer;
    }

    static List<AbstractInsnNode> next(AbstractInsnNode from, int n) {
        List<AbstractInsnNode> out = new ArrayList<AbstractInsnNode>(n);
        AbstractInsnNode cur = from;
        for (int i = 0; i < n; i++) {
            cur = cur == null ? null : cur.getNext();
            out.add(cur);
        }
        return out;
    }

    static boolean opcodes(List<AbstractInsnNode> insns, int... ops) {
        if (insns.size() < ops.length) return false;
        for (int i = 0; i < ops.length; i++) {
            AbstractInsnNode n = insns.get(i);
            if (n == null || n.getOpcode() != ops[i]) return false;
        }
        return true;
    }
}
