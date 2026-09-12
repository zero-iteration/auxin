package dev.auxin.staticscan.scan;

import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodBodyShape;
import dev.auxin.staticscan.model.MethodModel;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads one class file into a {@link ClassModel}: members, access flags, {@code SourceFile}, the
 * first line of each method, annotation descriptors, and a coarse shape of each body.
 *
 * <p>WHY it collects annotations and body shape in the same pass rather than leaving them to the
 * detectors that need them: the alternative is re-reading every class file once per detector. One
 * pass that gathers facts, and pure functions over those facts, keeps every detector trivially
 * testable without an ASM fixture of its own.
 *
 * <p>WHY {@code SKIP_FRAMES} but not {@code SKIP_CODE}: stack-map frames tell us nothing, but the
 * {@code LineNumberTable} and the body shape both live inside the {@code Code} attribute. The
 * manifest's {@code line} field is what turns a verdict into something a human can go and look at,
 * and the body shape is what C51 is decided from.
 */
public final class InventoryScanner {

    /**
     * Scans one class.
     *
     * @param originPath jar entry name or file path, carried into the model for test detection
     * @return the model, or {@code null} for {@code module-info} and {@code package-info}, which
     *         declare no executable members and would only add empty entries to the manifest
     */
    public ClassModel scan(String originPath, byte[] classBytes) {
        InventoryClassVisitor visitor = new InventoryClassVisitor(originPath);
        new ClassReader(classBytes).accept(visitor, ClassReader.SKIP_FRAMES);
        return visitor.result();
    }

    /** Collects class-level facts and delegates each method to {@link InventoryMethodVisitor}. */
    private static final class InventoryClassVisitor extends ClassVisitor {

        private final String originPath;
        private final Set<String> annotations = new LinkedHashSet<>();
        private final List<InventoryMethodVisitor> methods = new ArrayList<>();
        private String name;
        private String internalName;
        private String sourceFile;
        private int access;
        private String superName;
        private List<String> interfaceNames = List.of();
        private boolean skip;

        private InventoryClassVisitor(String originPath) {
            super(Opcodes.ASM9);
            this.originPath = originPath;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.internalName = name;
            this.name = name.replace('/', '.');
            this.access = access;
            this.superName = superName == null ? null : superName.replace('/', '.');
            this.interfaceNames = interfaces == null ? List.of() : dotted(interfaces);
            // A module descriptor has no members; package-info exists only to hold annotations.
            this.skip = (access & Opcodes.ACC_MODULE) != 0
                    || this.name.endsWith("package-info")
                    || this.name.endsWith("module-info");
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // Both retentions are collected: whether an annotation survives to runtime says nothing
            // about whether it marks an entry point at build time.
            annotations.add(descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (skip) {
                return null;
            }
            InventoryMethodVisitor method = new InventoryMethodVisitor(access, name, descriptor);
            methods.add(method);
            return method;
        }

        /** Only valid once {@code ClassReader.accept} has returned. */
        private ClassModel result() {
            if (skip || name == null) {
                return null;
            }
            List<MethodModel> models = new ArrayList<>(methods.size());
            for (InventoryMethodVisitor method : methods) {
                models.add(method.toModel());
            }
            return new ClassModel(name, internalName, sourceFile, access, superName,
                    interfaceNames, annotations, models, originPath);
        }

        private static List<String> dotted(String[] internalNames) {
            List<String> out = new ArrayList<>(internalNames.length);
            for (String internal : internalNames) {
                out.add(internal.replace('/', '.'));
            }
            return out;
        }
    }

    /**
     * Accumulates one method's annotations, first line, and body shape.
     *
     * <p>Every instruction-visiting method is overridden purely to keep an accurate instruction
     * count. That matters because C51's classification turns on "one or two instructions", and a
     * count that quietly excluded, say, {@code LDC} would misclassify exactly the constant-returning
     * accessors the rule exists for.
     */
    private static final class InventoryMethodVisitor extends MethodVisitor {

        private final int access;
        private final String name;
        private final String descriptor;
        private final Set<String> annotations = new LinkedHashSet<>();
        private final int[] leadingOpcodes = new int[MethodBodyShape.LEADING_OPCODES];
        private int line = -1;
        private int instructionCount;
        private boolean hasFieldWrite;
        private boolean hasJump;
        private int invokeCount;
        private int constructorInvokeCount;
        private boolean hasInvokeDynamic;
        private boolean hasThrow;

        private InventoryMethodVisitor(int access, String name, String descriptor) {
            super(Opcodes.ASM9);
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            Arrays.fill(leadingOpcodes, -1);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add(descriptor);
            return null;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            // The minimum, not the first visited: the table is not required to be ordered, and the
            // manifest's promise is "where does this method start in the source".
            if (this.line < 0 || line < this.line) {
                this.line = line;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.ATHROW) {
                hasThrow = true;
            }
            record(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            record(opcode);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            record(opcode);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            record(opcode);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
            if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                hasFieldWrite = true;
            }
            record(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc,
                                    boolean isInterface) {
            invokeCount++;
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(methodName)) {
                constructorInvokeCount++;
            }
            record(opcode);
        }

        @Override
        public void visitInvokeDynamicInsn(String indyName, String indyDesc, Handle bootstrapMethod,
                                           Object... bootstrapMethodArguments) {
            hasInvokeDynamic = true;
            record(Opcodes.INVOKEDYNAMIC);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            hasJump = true;
            record(opcode);
        }

        @Override
        public void visitLdcInsn(Object value) {
            record(Opcodes.LDC);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            record(Opcodes.IINC);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            hasJump = true;
            record(Opcodes.TABLESWITCH);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            hasJump = true;
            record(Opcodes.LOOKUPSWITCH);
        }

        @Override
        public void visitMultiANewArrayInsn(String arrayDesc, int numDimensions) {
            record(Opcodes.MULTIANEWARRAY);
        }

        private void record(int opcode) {
            if (instructionCount < leadingOpcodes.length) {
                leadingOpcodes[instructionCount] = opcode;
            }
            instructionCount++;
        }

        private MethodModel toModel() {
            MethodBodyShape shape = instructionCount == 0
                    ? MethodBodyShape.NO_BODY
                    : new MethodBodyShape(instructionCount, leadingOpcodes, hasFieldWrite, hasJump,
                            invokeCount, constructorInvokeCount, hasInvokeDynamic, hasThrow);
            return new MethodModel(name, descriptor, access, line, annotations, shape);
        }
    }
}
