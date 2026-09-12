package dev.auxin.staticscan.scan;

import dev.auxin.staticscan.model.MethodBodyShape;
import dev.auxin.staticscan.model.MethodModel;
import org.objectweb.asm.Opcodes;

/**
 * Decides whether a method's execution could ever be distinguishable at runtime (C51).
 *
 * <p>TOSEM 2022, verbatim: <i>"Primitive constants, custom exceptions, and single-instruction
 * methods... are not part of the executable code in the bytecode, and cannot be covered
 * dynamically."</i> For such a method, "never observed" carries no information at all -- and if we
 * treat it as evidence, we report compile-time constants as dead code.
 *
 * <p>WHY the classification is deliberately generous: the contract from the brief is "when in doubt,
 * emit false". A {@code false} here only ever forces a method towards UNKNOWN; a wrong {@code true}
 * lets a trivial accessor be proposed for deletion on the strength of evidence that never existed.
 * The asymmetry is the whole point, so the shapes below are matched conservatively and anything
 * larger is assumed observable.
 */
public final class DynamicObservability {

    /**
     * {@code false} when runtime absence of this method proves nothing.
     *
     * <p>The shapes ruled not observable:
     * <ul>
     *   <li>no body at all (abstract, native, or a stripped {@code Code} attribute);</li>
     *   <li>one or two instructions -- an empty {@code return}, a constant return, {@code return
     *       this}, a static-field read;</li>
     *   <li>a plain field accessor, {@code ALOAD_0 / GETFIELD / xRETURN};</li>
     *   <li>a constructor that does nothing but delegate, which is the shape of essentially every
     *       custom exception class.</li>
     * </ul>
     */
    public boolean isObservable(MethodModel method) {
        if (method.isAbstract() || method.isNative()) {
            return false;
        }
        MethodBodyShape shape = method.bodyShape();
        if (shape.instructionCount() == 0) {
            return false;
        }
        if (shape.instructionCount() <= 2) {
            return false;
        }
        if (isPlainFieldAccessor(shape)) {
            return false;
        }
        return !isDelegatingConstructor(method, shape);
    }

    private boolean isPlainFieldAccessor(MethodBodyShape shape) {
        return shape.instructionCount() == 3
                && shape.opcodeAt(0) == Opcodes.ALOAD
                && shape.opcodeAt(1) == Opcodes.GETFIELD
                && isReturn(shape.opcodeAt(2));
    }

    /**
     * A constructor whose body is nothing but argument loads, one {@code super(...)} or
     * {@code this(...)} call, and a return. Custom exception types are almost always exactly this,
     * and TOSEM names them explicitly.
     */
    private boolean isDelegatingConstructor(MethodModel method, MethodBodyShape shape) {
        return method.isConstructor()
                && !shape.hasFieldWrite()
                && !shape.hasJump()
                && !shape.hasInvokeDynamic()
                && !shape.hasThrow()
                && shape.invokeCount() == 1
                && shape.constructorInvokeCount() == 1;
    }

    private boolean isReturn(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }
}
