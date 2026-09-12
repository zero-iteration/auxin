package ax.bench.gen;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Emits the three (+1 bonus) probe patterns of gate G1 as raw ASM instruction lists.
 *
 * Non-negotiables honoured here (PLAN-v2):
 *   - raw ASM, never ByteBuddy Advice
 *   - ClassWriter(0): frames are emitted by hand, never COMPUTE_FRAMES (it loads classes)
 *   - condy so the probe array reference is a JIT constant and the BASTORE bounds check folds away
 *
 * HOISTING RESTRICTION (measured finding, see RESULTS.md): the hoisted arms park the probe array
 * in a fresh local slot. That slot must sit immediately after the method arguments, and every
 * pre-existing StackMapTable frame in the method must then be rewritten to mention it (this is
 * exactly what JaCoCo's ProbeInserter does, shifting all other locals up by one). This harness
 * therefore only applies the hoisted arms to methods it GENERATES (no pre-existing frames).
 */
public final class ProbeEmitter {

    public static final String RUNTIME = "gt/bench/rt/GtBenchRuntime";
    public static final String CONDY_NAME = "$axProbes";
    public static final String BSM_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;I)[Z";

    private ProbeEmitter() {}

    public static Handle bsm() {
        return new Handle(Opcodes.H_INVOKESTATIC, RUNTIME, "bootstrap", BSM_DESC, false);
    }

    /**
     * @param rawArrayDescriptor when true the condy is declared as "[Z" directly (3 bytes cheaper per
     *                           site, no CHECKCAST) instead of the JaCoCo JDK-8216970 workaround.
     *                           G3 tests whether that verifies on this JDK.
     */
    public static ConstantDynamic condy(String owner, int probeCount, boolean rawArrayDescriptor) {
        return new ConstantDynamic(CONDY_NAME, rawArrayDescriptor ? "[Z" : "Ljava/lang/Object;",
                                   bsm(), owner, probeCount);
    }

    public static ConstantDynamic condy(String owner, int probeCount) {
        return condy(owner, probeCount, false);
    }

    /** JaCoCo InstrSupport.push() semantics: smallest possible encoding for the probe index. */
    public static void push(InsnList out, int value) {
        if (value >= -1 && value <= 5) {
            out.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value <= Byte.MAX_VALUE) {
            out.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value <= Short.MAX_VALUE) {
            out.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            out.add(new LdcInsnNode(value));
        }
    }

    private static void loadArray(InsnList out, ConstantDynamic condy, int hoistSlot) {
        if (hoistSlot >= 0) {
            out.add(new VarInsnNode(Opcodes.ALOAD, hoistSlot));
        } else {
            out.add(new LdcInsnNode(condy));
            if (!"[Z".equals(condy.getDescriptor())) {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Z"));
            }
        }
    }

    /**
     * Builds the whole probe prologue for one method.
     *
     * @param hoistSlot  local slot for the hoisted array, or -1 for the non-hoisting arms.
     *                   Must equal the size of the locals at method entry (argsSize) so the
     *                   emitted F_APPEND frame is truthful.
     */
    public static InsnList prologue(ProbeStyle style, ConstantDynamic condy, int[] probeIdx, int hoistSlot) {
        InsnList out = new InsnList();
        if (style == ProbeStyle.NONE || probeIdx.length == 0) return out;

        if (style.hoists()) {
            out.add(new LdcInsnNode(condy));
            if (!"[Z".equals(condy.getDescriptor())) out.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Z"));
            out.add(new VarInsnNode(Opcodes.ASTORE, hoistSlot));
        }
        int effectiveSlot = style.hoists() ? hoistSlot : -1;

        boolean appended = false;
        for (int idx : probeIdx) {
            if (style.branches()) {
                LabelNode skip = new LabelNode();
                loadArray(out, condy, effectiveSlot);
                push(out, idx);
                out.add(new InsnNode(Opcodes.BALOAD));
                out.add(new JumpInsnNode(Opcodes.IFNE, skip));
                loadArray(out, condy, effectiveSlot);
                push(out, idx);
                out.add(new InsnNode(Opcodes.ICONST_1));
                out.add(new InsnNode(Opcodes.BASTORE));
                out.add(skip);
                // hand-written stack map frame: this is the cost arm (b) and (c) pay and arm (a) does not
                if (style.hoists() && !appended) {
                    out.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"[Z"}, 0, null));
                    appended = true;
                } else {
                    out.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                }
            } else {
                loadArray(out, condy, effectiveSlot);
                push(out, idx);
                out.add(new InsnNode(Opcodes.ICONST_1));
                out.add(new InsnNode(Opcodes.BASTORE));
            }
        }
        return out;
    }

    /** Instruments a generated (frame-free) method. Returns the number of probes inserted. */
    public static int instrumentGenerated(MethodNode m, ProbeStyle style, ConstantDynamic condy, int[] probeIdx) {
        if (style == ProbeStyle.NONE || probeIdx.length == 0) return 0;
        int argsSize = (Type.getArgumentsAndReturnSizes(m.desc) >> 2)
                       - ((m.access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
        int hoistSlot = style.hoists() ? argsSize : -1;
        m.instructions.insert(prologue(style, condy, probeIdx, hoistSlot));
        m.maxStack = Math.max(m.maxStack, 3);
        if (style.hoists()) m.maxLocals = Math.max(m.maxLocals, argsSize + 1);
        return probeIdx.length;
    }

}
