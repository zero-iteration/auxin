package dev.auxin.staticscan.model;

import dev.auxin.manifest.CallEdge;

/**
 * One unresolved reference site: "method X mentions Y, via this kind of instruction".
 *
 * <p>WHY this sits between the bytecode scan and the call graph: resolution needs the whole class
 * hierarchy, and the hierarchy is only complete once every class has been read. Emitting a
 * {@link CallEdge} directly from the scanner would force either a second pass over the bytes or a
 * guess about types not yet seen.
 *
 * <p>For the three {@code TYPE_*} kinds the target is a bare type, and {@code targetName} is
 * {@link CallEdge#TYPE_REFERENCE} with an empty descriptor.
 */
public record RawInvocation(MethodRef from,
                            String targetClass,
                            String targetName,
                            String targetDesc,
                            InvocationKind kind) {

    /** Builds a reference to a bare type (instanceof, class literal, catch clause). */
    public static RawInvocation ofType(MethodRef from, String targetClass, InvocationKind kind) {
        return new RawInvocation(from, targetClass, CallEdge.TYPE_REFERENCE, "", kind);
    }

    public boolean isTypeReference() {
        return CallEdge.TYPE_REFERENCE.equals(targetName);
    }

    /** The canonical manifest form of the target endpoint. */
    public String targetRef() {
        return isTypeReference()
                ? CallEdge.typeRef(targetClass)
                : CallEdge.ref(targetClass, targetName, targetDesc);
    }

    public MethodRef targetAsMethodRef() {
        return new MethodRef(targetClass, targetName, targetDesc);
    }
}
