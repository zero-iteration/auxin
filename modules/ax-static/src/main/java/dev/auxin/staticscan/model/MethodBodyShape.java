package dev.auxin.staticscan.model;

/**
 * A coarse summary of what a method body actually contains.
 *
 * <p>WHY we summarise instead of keeping the instruction list: the only question downstream is C51's
 * -- "could this method's execution ever be distinguishable at runtime?" -- and answering it needs
 * the first few opcodes plus a handful of booleans, not the body. Keeping the body would make the
 * scanner's memory grow with the size of the artifact for no benefit.
 *
 * <p>{@link #instructionCount()} counts real instructions only: labels, line-number markers and
 * stack-map frames are bookkeeping, not code, and counting them would make every method look
 * non-trivial.
 */
public record MethodBodyShape(int instructionCount,
                              int[] leadingOpcodes,
                              boolean hasFieldWrite,
                              boolean hasJump,
                              int invokeCount,
                              int constructorInvokeCount,
                              boolean hasInvokeDynamic,
                              boolean hasThrow) {

    /** How many leading opcodes are retained. Three is enough for every shape we classify. */
    public static final int LEADING_OPCODES = 4;

    /** The shape of a method with no {@code Code} attribute at all (abstract or native). */
    public static final MethodBodyShape NO_BODY =
            new MethodBodyShape(0, new int[0], false, false, 0, 0, false, false);

    public MethodBodyShape {
        leadingOpcodes = leadingOpcodes.clone();
    }

    @Override
    public int[] leadingOpcodes() {
        return leadingOpcodes.clone();
    }

    /** Opcode at {@code index} among the leading instructions, or {@code -1} if there is none. */
    public int opcodeAt(int index) {
        int[] ops = leadingOpcodes;
        return index >= 0 && index < ops.length ? ops[index] : -1;
    }
}
