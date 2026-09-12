package dev.auxin.staticscan.entry;

import dev.auxin.manifest.EntryPoint;
import dev.auxin.staticscan.graph.ClassHierarchy;
import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the methods the outside world can invoke with no caller inside the artifact.
 *
 * <p>WHY this is not optional: an HTTP handler, a Kafka listener and a {@code ServiceLoader} provider
 * all have zero static callers. A reachability analysis with no roots declares the entire
 * application dead, so the roots are the part of the static picture that has to be right even though
 * the graph itself does not. And under SCOPE-v3 the entry points are also what tier-2 selects
 * automatically, so a boundary this class misses reports no TPS, no error rate and no p90 anywhere.
 *
 * <p>There are two mechanisms, because applications declare their boundary in two ways:
 * <ol>
 *   <li><b>Annotation descriptors</b> ({@link EntryPointCatalog}) -- {@code @GetMapping},
 *       {@code @KafkaListener}, {@code @Scheduled}, plus {@code main} and
 *       {@code META-INF/services}.</li>
 *   <li><b>Interface and superclass implementation</b> ({@link BoundaryCatalog}) -- a class that
 *       implements {@code HttpHandler}, {@code Servlet}, {@code Filter}, {@code MessageListener} or
 *       a Netty/Undertow handler carries no annotation at all, and an annotation-only detector found
 *       nothing in such an application. The match is on the declaring type <em>and</em> the exact
 *       boundary signature, so an unrelated {@code handle(String)} is not mistaken for one.</li>
 * </ol>
 *
 * <p>Decisions worth stating, because each is a judgement call:
 * <ul>
 *   <li><b>Type-level annotations propagate to public methods only.</b> A type-level
 *       {@code @RequestMapping} or {@code @Path} makes the class a request surface, but a private
 *       helper inside it is not an HTTP endpoint under any reading. Constructors are excluded for
 *       the same reason. Propagating to every member would be conservative in the wrong place --
 *       it would flood the entry-point list and make it useless as evidence.</li>
 *   <li><b>A {@code META-INF/services} provider is recorded on its no-argument constructor</b>,
 *       because that is the member {@link java.util.ServiceLoader} actually invokes. It is emitted
 *       whether or not the class was found in the scan: the declaration is the evidence.</li>
 *   <li><b>A boundary match needs a body.</b> An abstract override, an interface declaration and a
 *       native method are skipped: they are the signature, not the implementation, and there is
 *       nothing there to time. A {@code default} method does have a body and is included. Synthetic
 *       and bridge members are skipped as always -- which is exactly why a generic boundary such as
 *       {@code Callable#call} is matched by shape rather than by the declaration's erasure; see
 *       {@link BoundarySignature}. A {@code static} method is skipped too: it can never implement an
 *       inherited instance signature, so a static method of the same name is an unrelated one.</li>
 *   <li><b>Boundary detection is bounded by the scan.</b> It reuses
 *       {@link ClassHierarchy#subtypesOf(String)}, which knows only what the scanned classes
 *       declare. A directly declared framework interface matches with no resolution at all; a
 *       hierarchy that passes through a type in another jar cannot be seen. {@link BoundaryCatalog}
 *       states the consequence in full.</li>
 * </ul>
 *
 * <p>Test classes are <em>not</em> filtered here. An entry point on a test class is a true fact
 * about the artifact and is reported as such; C50 is applied by the consumers -- tier-2 selection
 * and test-linkage analysis both drop them -- so the two rules stay separable.
 */
public final class EntryPointDetector {

    private final EntryPointCatalog catalog;
    private final BoundaryCatalog boundaries;

    public EntryPointDetector(EntryPointCatalog catalog, BoundaryCatalog boundaries) {
        this.catalog = catalog;
        this.boundaries = boundaries;
    }

    /**
     * @param classes          every scanned class
     * @param serviceProviders class names declared under {@code META-INF/services}
     * @param hierarchy        the subtype relation over the same classes, reused rather than rebuilt
     */
    public List<EntryPoint> detect(Collection<ClassModel> classes,
                                   Set<String> serviceProviders,
                                   ClassHierarchy hierarchy) {
        List<EntryPoint> entryPoints = new ArrayList<>();
        for (ClassModel owner : classes) {
            detectInClass(owner, entryPoints);
        }
        detectBoundaries(hierarchy, entryPoints);
        for (String provider : serviceProviders) {
            entryPoints.add(new EntryPoint(provider, "<init>", "()V",
                    EntryPointCatalog.KIND_SERVICE_LOADER));
        }
        return entryPoints;
    }

    private void detectInClass(ClassModel owner, List<EntryPoint> entryPoints) {
        List<String> classLevelKinds = new ArrayList<>();
        for (String descriptor : owner.annotationDescriptors()) {
            if (catalog.appliesAtClassLevel(descriptor)) {
                String kind = catalog.kindOf(descriptor);
                if (kind != null) {
                    classLevelKinds.add(kind);
                }
            }
        }

        for (MethodModel method : owner.methods()) {
            // Compiler-generated members are never an author-declared entry point.
            if (method.isSynthetic() || method.isBridge() || method.isClassInitializer()) {
                continue;
            }
            for (String descriptor : method.annotationDescriptors()) {
                String kind = catalog.kindOf(descriptor);
                if (kind != null) {
                    entryPoints.add(new EntryPoint(owner.name(), method.name(), method.desc(), kind));
                }
            }
            if (isMain(method)) {
                entryPoints.add(new EntryPoint(owner.name(), method.name(), method.desc(),
                        EntryPointCatalog.KIND_MAIN));
            }
            if (!classLevelKinds.isEmpty() && method.isPublic() && !method.isConstructor()) {
                for (String kind : classLevelKinds) {
                    entryPoints.add(new EntryPoint(owner.name(), method.name(), method.desc(), kind));
                }
            }
        }
    }

    /**
     * The interface/superclass mechanism: one downward walk of the hierarchy per boundary type, and
     * a signature check on each implementor's own declared methods.
     *
     * <p>Driven from the catalogue rather than from the class list because
     * {@link ClassHierarchy#subtypesOf(String)} already answers "who implements this, transitively?"
     * and caches it. Walking upwards from every class would mean building a second index of the same
     * relation.
     */
    private void detectBoundaries(ClassHierarchy hierarchy, List<EntryPoint> entryPoints) {
        for (Map.Entry<String, List<BoundarySignature>> boundary
                : boundaries.byDeclaringType().entrySet()) {
            for (String implementor : hierarchy.subtypesOf(boundary.getKey())) {
                ClassModel owner = hierarchy.get(implementor);
                if (owner == null) {
                    continue;
                }
                for (MethodModel method : owner.methods()) {
                    if (!isImplementation(method)) {
                        continue;
                    }
                    for (BoundarySignature signature : boundary.getValue()) {
                        if (signature.matches(method.name(), method.desc())) {
                            entryPoints.add(new EntryPoint(owner.name(), method.name(),
                                    method.desc(), signature.kind()));
                            break;
                        }
                    }
                }
            }
        }
    }

    /** Whether this member could be the author-written body a framework dispatches into. */
    private boolean isImplementation(MethodModel method) {
        return !method.isAbstract()
                && !method.isNative()
                && !method.isSynthetic()
                && !method.isBridge()
                && !method.isStatic()
                && !method.isConstructor()
                && !method.isClassInitializer();
    }

    /** The JVM launcher's contract: {@code public static void main(String[])}, exactly. */
    private boolean isMain(MethodModel method) {
        return "main".equals(method.name())
                && EntryPointCatalog.MAIN_DESCRIPTOR.equals(method.desc())
                && method.isPublic()
                && (method.access() & Opcodes.ACC_STATIC) != 0;
    }
}
