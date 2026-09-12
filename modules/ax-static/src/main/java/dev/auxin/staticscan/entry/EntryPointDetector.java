package dev.auxin.staticscan.entry;

import dev.auxin.manifest.EntryPoint;
import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Finds the methods the outside world can invoke with no caller inside the artifact.
 *
 * <p>WHY this is not optional: an HTTP handler, a Kafka listener and a {@code ServiceLoader} provider
 * all have zero static callers. A reachability analysis with no roots declares the entire
 * application dead, so the roots are the part of the static picture that has to be right even though
 * the graph itself does not.
 *
 * <p>Two decisions worth stating, because both are judgement calls and both err towards keeping code
 * alive:
 * <ul>
 *   <li><b>Type-level annotations propagate to public methods only.</b> A type-level
 *       {@code @RequestMapping} or {@code @Path} makes the class a request surface, but a private
 *       helper inside it is not an HTTP endpoint under any reading. Constructors are excluded for
 *       the same reason. Propagating to every member would be conservative in the wrong place --
 *       it would flood the entry-point list and make it useless as evidence.</li>
 *   <li><b>A {@code META-INF/services} provider is recorded on its no-argument constructor</b>,
 *       because that is the member {@link java.util.ServiceLoader} actually invokes. It is emitted
 *       whether or not the class was found in the scan: the declaration is the evidence.</li>
 * </ul>
 */
public final class EntryPointDetector {

    private final EntryPointCatalog catalog;

    public EntryPointDetector(EntryPointCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param classes          every scanned class
     * @param serviceProviders class names declared under {@code META-INF/services}
     */
    public List<EntryPoint> detect(Collection<ClassModel> classes, Set<String> serviceProviders) {
        List<EntryPoint> entryPoints = new ArrayList<>();
        for (ClassModel owner : classes) {
            detectInClass(owner, entryPoints);
        }
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

    /** The JVM launcher's contract: {@code public static void main(String[])}, exactly. */
    private boolean isMain(MethodModel method) {
        return "main".equals(method.name())
                && EntryPointCatalog.MAIN_DESCRIPTOR.equals(method.desc())
                && method.isPublic()
                && (method.access() & Opcodes.ACC_STATIC) != 0;
    }
}
