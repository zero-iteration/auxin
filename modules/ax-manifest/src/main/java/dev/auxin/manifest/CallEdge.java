package dev.auxin.manifest;

import java.util.Objects;

/**
 * One directed reference in the corroborating static call graph.
 *
 * <p>WHY this type is immutable and string-keyed: the edge endpoints are the frozen
 * {@code "com.acme.Foo#bar(I)V"} reference form from CONTRACTS section 1. Keeping them as opaque
 * strings means gt-collector and gt-analysis never have to agree with ax-static on a parsed method
 * model -- only on this one textual convention.
 *
 * <p>WHY both {@link #resolution()} and {@link #semantics()}: PLAN-v2 demotes this graph to a
 * corroborating signal because it is roughly 61% unsound. The two labels are what keep it honest --
 * one says how sure we are about the callee, the other says whether the reference blocks deletion
 * at all (C52). Neither may be inferred from the other.
 */
public final class CallEdge {

    /**
     * Pseudo-method name used when an edge points at a <em>type</em> rather than a method -- an
     * {@code instanceof} test, a {@code .class} literal or a catch-clause type. The JVM forbids
     * {@code <} and {@code >} in real method names except for {@code <init>}/{@code <clinit>}, so
     * this token cannot collide with a genuine member.
     */
    public static final String TYPE_REFERENCE = "<type>";

    private final String from;
    private final String to;
    private final Resolution resolution;
    private final EdgeSemantics semantics;

    public CallEdge(String from, String to, Resolution resolution, EdgeSemantics semantics) {
        this.from = require(from, "from");
        this.to = require(to, "to");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.semantics = Objects.requireNonNull(semantics, "semantics");
    }

    /** Builds the canonical {@code "com.acme.Foo#bar(I)V"} endpoint reference. */
    public static String ref(String className, String methodName, String descriptor) {
        return require(className, "className") + '#' + require(methodName, "methodName")
                + (descriptor == null ? "" : descriptor);
    }

    /** Builds the canonical endpoint reference for a bare type reference (see {@link #TYPE_REFERENCE}). */
    public static String typeRef(String className) {
        return require(className, "className") + '#' + TYPE_REFERENCE;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public Resolution resolution() {
        return resolution;
    }

    public EdgeSemantics semantics() {
        return semantics;
    }

    private static String require(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or empty");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CallEdge)) {
            return false;
        }
        CallEdge other = (CallEdge) o;
        return from.equals(other.from)
                && to.equals(other.to)
                && resolution == other.resolution
                && semantics == other.semantics;
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, resolution, semantics);
    }

    @Override
    public String toString() {
        return from + " -> " + to + " [" + resolution.wireName() + "/" + semantics.wireName() + "]";
    }
}
