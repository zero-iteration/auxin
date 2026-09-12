package dev.auxin.staticscan.graph;

import dev.auxin.staticscan.model.InvocationKind;
import dev.auxin.staticscan.model.MethodRef;
import dev.auxin.staticscan.model.RawInvocation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks method bodies and records every reference site, unresolved.
 *
 * <p>WHY the {@code invokedynamic} handling is split in two: a {@code LambdaMetafactory} bootstrap
 * names its implementation method directly in the {@code BootstrapMethods} attribute, so the edge
 * from the enclosing method to the lambda body is genuinely exact. Every other bootstrap -- string
 * concatenation, records, a framework's own -- is opaque to us. The brief is explicit that an
 * {@code invokedynamic} we cannot resolve is <b>emitted as unresolved, never dropped</b>: a dropped
 * edge is a claim of absence we did not earn.
 *
 * <p>The three {@code TYPE_*} kinds implement C52's no-op catalogue. An {@code instanceof} test, a
 * {@code .class} literal and a catch-clause type all reference a type without depending on its
 * behaviour, so the reference constant-folds away with the type. Recording them as <em>edges</em>
 * rather than not at all is what lets the analysis see the reference and correctly decide it does
 * not block removal.
 */
public final class CallSiteScanner {

    /** Scans one class file and returns every reference site found in its method bodies. */
    public List<RawInvocation> scan(byte[] classBytes) {
        CallSiteClassVisitor visitor = new CallSiteClassVisitor();
        new ClassReader(classBytes).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return visitor.invocations;
    }

    private static final class CallSiteClassVisitor extends ClassVisitor {

        private final List<RawInvocation> invocations = new ArrayList<>();
        private String className;

        private CallSiteClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = name.replace('/', '.');
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return null;
            }
            return new CallSiteMethodVisitor(new MethodRef(className, name, descriptor), invocations);
        }
    }

    private static final class CallSiteMethodVisitor extends MethodVisitor {

        private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

        private final MethodRef from;
        private final List<RawInvocation> out;

        private CallSiteMethodVisitor(MethodRef from, List<RawInvocation> out) {
            super(Opcodes.ASM9);
            this.from = from;
            this.out = out;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            InvocationKind kind = switch (opcode) {
                case Opcodes.INVOKESTATIC -> InvocationKind.STATIC;
                case Opcodes.INVOKESPECIAL -> InvocationKind.SPECIAL;
                case Opcodes.INVOKEINTERFACE -> InvocationKind.INTERFACE;
                default -> InvocationKind.VIRTUAL;
            };
            out.add(new RawInvocation(from, dotted(owner), name, descriptor, kind));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethod,
                                           Object... bootstrapMethodArguments) {
            if (LAMBDA_METAFACTORY.equals(bootstrapMethod.getOwner())) {
                // metafactory puts the implementation handle at argument 1; altMetafactory adds
                // bridge handles. Every Handle argument names a real method, so emit them all.
                boolean emitted = false;
                for (Object argument : bootstrapMethodArguments) {
                    if (argument instanceof Handle handle) {
                        out.add(new RawInvocation(from, dotted(handle.getOwner()), handle.getName(),
                                handle.getDesc(), InvocationKind.DYNAMIC_LAMBDA));
                        emitted = true;
                    }
                }
                if (emitted) {
                    return;
                }
                // A LambdaMetafactory call site with no method handle is not something we
                // understand, so it falls through to the opaque case rather than vanishing.
            }
            out.add(new RawInvocation(from, dotted(bootstrapMethod.getOwner()),
                    bootstrapMethod.getName(), bootstrapMethod.getDesc(),
                    InvocationKind.DYNAMIC_OPAQUE));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.INSTANCEOF) {
                out.add(RawInvocation.ofType(from, dotted(type), InvocationKind.TYPE_INSTANCEOF));
            }
            // NEW, ANEWARRAY and CHECKCAST are deliberately absent: they are not no-ops. A NEW
            // needs the type to exist and a CHECKCAST asserts a real dependency on it.
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type type
                    && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
                out.add(RawInvocation.ofType(from, type.getClassName(),
                        InvocationKind.TYPE_CLASS_LITERAL));
            }
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                // A null type is a finally block, which references nothing.
                out.add(RawInvocation.ofType(from, dotted(type), InvocationKind.TYPE_CATCH));
            }
        }

        private static String dotted(String internalName) {
            return internalName.replace('/', '.');
        }
    }
}
