package ax.bench.gen;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Generates the G1 subject classes. Every method is probed at entry, which is the Tier-1
 * (method-granular) shape. All probes for one class live in one boolean[] - at 1 byte per element
 * that is 64 probes per x86_64 cache line and 128 per Apple M-series line, so the multi-threaded
 * arms of G1 are measuring true false sharing, not an artefact.
 */
public final class WorkerGenerator {

    public static final int PRIME = 0x9E3779B1;

    private WorkerGenerator() {}

    /**
     * @param internalName    e.g. "gt/bench/g1/gen/W_BLIND"
     * @param leafMethods     number of small private leaf methods work() chains through
     * @param probesPerMethod probe sites per leaf method (1 = Tier-1 method granularity)
     */
    public static byte[] generate(String internalName, ProbeStyle style,
                                  int leafMethods, int probesPerMethod, boolean implementsWorker) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";
        if (implementsWorker) cn.interfaces.add("gt/bench/g1/Worker");

        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1; init.maxLocals = 1;
        cn.methods.add(init);

        int probeCount = 1 + leafMethods * probesPerMethod;
        ConstantDynamic condy = ProbeEmitter.condy(internalName, probeCount);
        int next = 0;

        MethodNode work = new MethodNode(Opcodes.ACC_PUBLIC, "work", "(I)I", null, null);
        for (int i = 0; i < leafMethods; i++) {
            work.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            work.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            work.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, internalName, "m" + i, "(I)I", false));
            work.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        }
        work.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        work.instructions.add(new InsnNode(Opcodes.IRETURN));
        work.maxStack = 2; work.maxLocals = 2;
        ProbeEmitter.instrumentGenerated(work, style, condy, new int[]{next++});
        cn.methods.add(work);

        for (int i = 0; i < leafMethods; i++) {
            MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, "m" + i, "(I)I", null, null);
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            m.instructions.add(new LdcInsnNode(PRIME));
            m.instructions.add(new InsnNode(Opcodes.IMUL));
            ProbeEmitter.push(m.instructions, i & 0x1F);
            m.instructions.add(new InsnNode(Opcodes.IADD));
            m.instructions.add(new InsnNode(Opcodes.IRETURN));
            m.maxStack = 2; m.maxLocals = 2;
            int[] idx = new int[probesPerMethod];
            for (int p = 0; p < probesPerMethod; p++) idx[p] = next++;
            ProbeEmitter.instrumentGenerated(m, style, condy, idx);
            cn.methods.add(m);
        }

        ClassWriter cw = new ClassWriter(0);   // NEVER COMPUTE_FRAMES - it loads classes
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Strips every StackMapTable so the frame contribution to class-file size can be isolated. */
    public static byte[] withoutFrames(byte[] cf) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cf).accept(cn, org.objectweb.asm.ClassReader.SKIP_FRAMES);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
