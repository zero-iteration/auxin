package dev.auxin.staticscan.model;

import dev.auxin.manifest.CallEdge;

import java.util.Objects;

/**
 * A fully-qualified reference to one method: the node identity of the call graph.
 *
 * <p>WHY a value type rather than the flat {@code "C#m(D)"} string used on the wire: the graph
 * builder needs to ask "does this class declare this member?" and "what is the owner of this
 * target?" thousands of times, and re-splitting a string on every question invites an off-by-one in
 * the one place -- method identity -- where an off-by-one silently misattributes coverage. The flat
 * form is produced once, at the boundary, by {@link #toRef()}.
 */
public record MethodRef(String className, String name, String desc) {

    public MethodRef {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(desc, "desc");
    }

    /** The canonical manifest form, {@code com.acme.Foo#bar(I)V}. */
    public String toRef() {
        return CallEdge.ref(className, name, desc);
    }

    /** {@code name + desc}: the override-identity of a member within a hierarchy. */
    public String nameAndDesc() {
        return name + desc;
    }

    @Override
    public String toString() {
        return toRef();
    }
}
