package dev.auxin.staticscan.graph;

import dev.auxin.manifest.CallEdge;
import dev.auxin.manifest.EdgeSemantics;
import dev.auxin.manifest.Resolution;
import dev.auxin.staticscan.model.MethodRef;

/**
 * A call-graph edge that still knows its endpoints as structured references.
 *
 * <p>WHY this exists alongside {@link CallEdge}: the manifest form flattens both ends to
 * {@code "C#m(D)"} strings, which is right for a wire contract and wrong for the component analysis
 * that has to walk the graph immediately afterwards. Re-parsing those strings to find the owner
 * would put string surgery in the middle of the one analysis whose mistakes are silent.
 */
public record ResolvedEdge(MethodRef from,
                           MethodRef to,
                           Resolution resolution,
                           EdgeSemantics semantics) {

    /** Projects onto the frozen manifest form. */
    public CallEdge toCallEdge() {
        return new CallEdge(from.toRef(), to.toRef(), resolution, semantics);
    }

    /** {@code true} if this edge constrains removal of its target (C52). */
    public boolean isBlocking() {
        return semantics == EdgeSemantics.BLOCKING;
    }
}
