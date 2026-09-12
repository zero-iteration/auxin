package ax.bench.g6;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates the G6 subject classes.
 *
 * <p>The instruction sequence is <b>the one {@code ProbeEmitter} emits</b>, and the calls land in
 * <b>the real {@code io.auxin.agent.runtime.EdgeRuntime}</b> out of the shipped agent jar — not a
 * copy of it in this module. That is the whole point: the number this gate produces has to be the
 * cost of the code that ships, including whatever HotSpot decides to do about inlining it.
 *
 * <pre>
 *   int work(int x) {                     // the sampling root
 *       EdgeRuntime.rootEnter(id);
 *       try {
 *           x = m0(x); ... x = m7(x);
 *           EdgeRuntime.rootExit(id);
 *           return x;
 *       } catch (Throwable t) { EdgeRuntime.rootExit(id); throw t; }
 *   }
 *   private int mN(int x) {               // a callee
 *       EdgeRuntime.enter(id);
 *       return (x * PRIME + N);           // ...with exit(id) emitted between the value and the
 *   }                                     //    return, on top of it, as the agent emits it
 * </pre>
 *
 * <p>Frames are computed here rather than hand-written: this is a benchmark subject, not an
 * agent, so {@code COMPUTE_FRAMES} loading a class is free. The frames do not affect the runtime
 * cost being measured — they are a load-time verification cost, which is what G1's class-size
 * report and the smoke suite's javap assertions measure instead.
 */
public final class EdgeWorkerGenerator {

    public static final String EDGE_RUNTIME = "io/auxin/agent/runtime/EdgeRuntime";
    public static final int PRIME = 0x9E3779B1;

    private EdgeWorkerGenerator() {}

    /**
     * @param internalName e.g. {@code ax/bench/g6/gen/W_EDGES}
     * @param leafMethods  small private leaf methods {@code work()} chains through
     * @param firstEdgeId  the edge id of {@code work()}; leaves get the next {@code leafMethods}
     */
    public static byte[] generate(String internalName, EdgeStyle style, int leafMethods,
                                  int firstEdgeId) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object",
                new String[]{"ax/bench/g6/EdgeWorker"});

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // ---- work(): the root ----
        MethodVisitor w = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "work", "(I)I", null, null);
        w.visitCode();
        final Label tryStart = new Label();
        final Label tryEnd = new Label();
        final Label handler = new Label();
        if (style.root()) {
            push(w, firstEdgeId);
            w.visitMethodInsn(Opcodes.INVOKESTATIC, EDGE_RUNTIME, "rootEnter", "(I)V", false);
            w.visitTryCatchBlock(tryStart, tryEnd, handler, null);
            w.visitLabel(tryStart);
        }
        for (int i = 0; i < leafMethods; i++) {
            w.visitVarInsn(Opcodes.ALOAD, 0);
            w.visitVarInsn(Opcodes.ILOAD, 1);
            w.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "m" + i, "(I)I", false);
            w.visitVarInsn(Opcodes.ISTORE, 1);
        }
        w.visitVarInsn(Opcodes.ILOAD, 1);
        if (style.root()) {
            push(w, firstEdgeId);
            w.visitMethodInsn(Opcodes.INVOKESTATIC, EDGE_RUNTIME, "rootExit", "(I)V", false);
        }
        w.visitInsn(Opcodes.IRETURN);
        if (style.root()) {
            w.visitLabel(tryEnd);
            w.visitLabel(handler);
            push(w, firstEdgeId);
            w.visitMethodInsn(Opcodes.INVOKESTATIC, EDGE_RUNTIME, "rootExit", "(I)V", false);
            w.visitInsn(Opcodes.ATHROW);
        }
        w.visitMaxs(0, 0);
        w.visitEnd();

        // ---- the leaves: callees ----
        for (int i = 0; i < leafMethods; i++) {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PRIVATE, "m" + i, "(I)I", null, null);
            m.visitCode();
            if (style.callees()) {
                push(m, firstEdgeId + 1 + i);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, EDGE_RUNTIME, "enter", "(I)V", false);
            }
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitLdcInsn(Integer.valueOf(PRIME));
            m.visitInsn(Opcodes.IMUL);
            push(m, i & 0x1F);
            m.visitInsn(Opcodes.IADD);
            if (style.callees()) {
                // The id goes ON TOP of the return value, exactly as ProbeEmitter emits it: no
                // local, no store, no reload. That is why the callee shape needs no stack map
                // frame, and the measurement has to keep it.
                push(m, firstEdgeId + 1 + i);
                m.visitMethodInsn(Opcodes.INVOKESTATIC, EDGE_RUNTIME, "exit", "(I)V", false);
            }
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void push(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) mv.visitInsn(Opcodes.ICONST_0 + value);
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, value);
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, value);
        else mv.visitLdcInsn(Integer.valueOf(value));
    }
}
