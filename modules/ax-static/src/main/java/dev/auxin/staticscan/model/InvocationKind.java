package dev.auxin.staticscan.model;

/**
 * What kind of bytecode site produced a {@link RawInvocation}.
 *
 * <p>WHY the raw site kind is preserved rather than a resolution label being decided on the spot:
 * whether an {@code invokevirtual} is {@code cha} or {@code unresolved} depends on whether its owner
 * turned out to be inside the scanned artifact, and that is not known until every class has been
 * read. Deciding early would force a second pass or a wrong label.
 */
public enum InvocationKind {

    /** {@code invokestatic} -- statically bound. */
    STATIC,

    /** {@code invokespecial} -- constructors, private methods, explicit super calls. */
    SPECIAL,

    /** {@code invokevirtual} -- virtual dispatch on a class type. */
    VIRTUAL,

    /** {@code invokeinterface} -- virtual dispatch on an interface type. */
    INTERFACE,

    /** {@code invokedynamic} whose bootstrap is LambdaMetafactory: the implementation is named. */
    DYNAMIC_LAMBDA,

    /** {@code invokedynamic} we do not understand. Emitted anyway, as {@code unresolved}. */
    DYNAMIC_OPAQUE,

    /** {@code instanceof} against a type -- a no-op reference under C52. */
    TYPE_INSTANCEOF,

    /** An {@code .class} literal in the constant pool -- a no-op reference under C52. */
    TYPE_CLASS_LITERAL,

    /** A catch-clause exception type -- a no-op reference under C52. */
    TYPE_CATCH
}
