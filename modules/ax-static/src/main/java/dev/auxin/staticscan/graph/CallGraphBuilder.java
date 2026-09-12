package dev.auxin.staticscan.graph;

import dev.auxin.manifest.EdgeSemantics;
import dev.auxin.manifest.Resolution;
import dev.auxin.staticscan.model.MethodRef;
import dev.auxin.staticscan.model.RawInvocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves raw reference sites into call edges, class-hierarchy analysis only.
 *
 * <p><b>This graph is known to be roughly 61% unsound and is a corroborating signal only</b> (A5,
 * PLAN-v2). It must never drive a deletion cascade. Everything below is arranged so that the graph
 * lies about its own completeness as little as possible:
 *
 * <table>
 *   <caption>Resolution rules</caption>
 *   <tr><th>site</th><th>resolution</th><th>why</th></tr>
 *   <tr><td>{@code invokestatic}, {@code invokespecial}</td><td>{@code exact}</td>
 *       <td>statically bound; no dispatch happens</td></tr>
 *   <tr><td>{@code invokevirtual}/{@code invokeinterface}, owner scanned</td><td>{@code cha}</td>
 *       <td>the declared target plus every scanned subtype that overrides it</td></tr>
 *   <tr><td>{@code invokevirtual}/{@code invokeinterface}, owner not scanned</td>
 *       <td>{@code unresolved}</td>
 *       <td>the implementors live outside the artifact; we genuinely cannot say</td></tr>
 *   <tr><td>{@code invokedynamic} via LambdaMetafactory</td><td>{@code exact}</td>
 *       <td>the implementation method is named in BootstrapMethods</td></tr>
 *   <tr><td>any other {@code invokedynamic}</td><td>{@code unresolved}</td>
 *       <td>emitted pointing at the bootstrap method, never dropped</td></tr>
 *   <tr><td>{@code instanceof}, {@code .class}, catch type</td><td>{@code exact} / {@code noop}</td>
 *       <td>the type is named exactly; the reference does not block removal (C52)</td></tr>
 * </table>
 *
 * <p>Note that {@code unresolved} is over-used on purpose. CONTRACTS section 4 forbids treating an
 * unresolved result as "not reachable", so marking too much of the graph unresolved can only make
 * verdicts more conservative -- which is the direction we want to fail in.
 *
 * <p>The one method-invocation site treated as a no-op is {@code Object.getClass()}, which queries a
 * type rather than using it. We do not attempt the dataflow needed to prove the result is only
 * compared, so all such sites are marked no-op; since it is a JDK method that will never be a
 * deletion candidate, the over-match costs nothing.
 */
public final class CallGraphBuilder {

    private static final String OBJECT_CLASS = "java.lang.Object";
    private static final String GET_CLASS = "getClass";
    private static final String GET_CLASS_DESC = "()Ljava/lang/Class;";

    private final ClassHierarchy hierarchy;

    public CallGraphBuilder(ClassHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    /** Resolves every site. The result is de-duplicated but otherwise in encounter order. */
    public List<ResolvedEdge> build(Collection<RawInvocation> invocations) {
        Set<ResolvedEdge> edges = new LinkedHashSet<>();
        for (RawInvocation invocation : invocations) {
            resolve(invocation, edges);
        }
        return new ArrayList<>(edges);
    }

    private void resolve(RawInvocation invocation, Set<ResolvedEdge> out) {
        MethodRef target = invocation.targetAsMethodRef();
        switch (invocation.kind()) {
            case STATIC, SPECIAL, DYNAMIC_LAMBDA ->
                    out.add(edge(invocation, target, Resolution.EXACT, EdgeSemantics.BLOCKING));
            case DYNAMIC_OPAQUE ->
                    out.add(edge(invocation, target, Resolution.UNRESOLVED, EdgeSemantics.BLOCKING));
            case TYPE_INSTANCEOF, TYPE_CLASS_LITERAL, TYPE_CATCH ->
                    out.add(edge(invocation, target, Resolution.EXACT, EdgeSemantics.NOOP));
            case VIRTUAL, INTERFACE -> resolveVirtual(invocation, target, out);
        }
    }

    private void resolveVirtual(RawInvocation invocation, MethodRef target, Set<ResolvedEdge> out) {
        if (isGetClass(target)) {
            out.add(edge(invocation, target, Resolution.UNRESOLVED, EdgeSemantics.NOOP));
            return;
        }
        if (!hierarchy.isScanned(target.className())) {
            out.add(edge(invocation, target, Resolution.UNRESOLVED, EdgeSemantics.BLOCKING));
            return;
        }
        out.add(edge(invocation, target, Resolution.CHA, EdgeSemantics.BLOCKING));
        String nameAndDesc = target.nameAndDesc();
        for (String subtype : hierarchy.subtypesOf(target.className())) {
            if (hierarchy.declares(subtype, nameAndDesc)) {
                out.add(edge(invocation, new MethodRef(subtype, target.name(), target.desc()),
                        Resolution.CHA, EdgeSemantics.BLOCKING));
            }
        }
    }

    private boolean isGetClass(MethodRef target) {
        return OBJECT_CLASS.equals(target.className())
                && GET_CLASS.equals(target.name())
                && GET_CLASS_DESC.equals(target.desc());
    }

    private ResolvedEdge edge(RawInvocation invocation, MethodRef target,
                              Resolution resolution, EdgeSemantics semantics) {
        return new ResolvedEdge(invocation.from(), target, resolution, semantics);
    }
}
