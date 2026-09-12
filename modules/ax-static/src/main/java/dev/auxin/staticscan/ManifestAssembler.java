package dev.auxin.staticscan;

import dev.auxin.manifest.CallEdge;
import dev.auxin.manifest.ClassEntry;
import dev.auxin.manifest.EntryPoint;
import dev.auxin.manifest.Manifest;
import dev.auxin.manifest.MethodCandidate;
import dev.auxin.manifest.MethodEntry;
import dev.auxin.manifest.ProbeIndex;
import dev.auxin.staticscan.api.PublicApiDetector;
import dev.auxin.staticscan.entry.EntryPointDetector;
import dev.auxin.staticscan.graph.CallGraphBuilder;
import dev.auxin.staticscan.graph.CallSiteScanner;
import dev.auxin.staticscan.graph.ClassHierarchy;
import dev.auxin.staticscan.graph.ResolvedEdge;
import dev.auxin.staticscan.linkage.MethodLinkage;
import dev.auxin.staticscan.linkage.TestClassifier;
import dev.auxin.staticscan.linkage.TestLinkageAnalyzer;
import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodRef;
import dev.auxin.staticscan.model.RawInvocation;
import dev.auxin.staticscan.scan.GeneratedClassFilter;
import dev.auxin.staticscan.scan.InventoryScanner;
import dev.auxin.staticscan.scan.MethodCandidateFactory;
import dev.auxin.staticscan.source.ClassSource;
import dev.auxin.staticscan.source.ClassSourceVisitor;

import java.io.IOException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wires the scanners and detectors together and produces the manifest.
 *
 * <p>WHY one orchestrator rather than each stage reading the input itself: the jar is read exactly
 * once. Both ASM passes -- inventory (signatures, lines, annotations, body shape) and call sites
 * (method bodies) -- run over the same in-memory byte array, which is then dropped. A per-stage read
 * would be three walks of a multi-hundred-megabyte artifact for no gain.
 *
 * <p>WHY the order of stages is fixed and not negotiable: the call graph cannot be resolved until
 * the class hierarchy is complete, and the component analysis cannot run until the graph exists.
 * Probe indices, by contrast, depend on nothing but the member list -- which is exactly the property
 * that makes them reproducible.
 */
public final class ManifestAssembler {

    /** Result of one scan: the manifest plus the counters an operator needs to trust it. */
    public record ScanResult(Manifest manifest,
                             int classesScanned,
                             int generatedClassesSkipped,
                             int serviceProvidersDeclared) {
    }

    private final InventoryScanner inventoryScanner;
    private final CallSiteScanner callSiteScanner;
    private final MethodCandidateFactory candidateFactory;
    private final EntryPointDetector entryPointDetector;
    private final PublicApiDetector publicApiDetector;
    private final TestClassifier testClassifier;
    private final TestLinkageAnalyzer testLinkageAnalyzer;
    private final GeneratedClassFilter generatedClassFilter;
    private final Clock clock;

    public ManifestAssembler(InventoryScanner inventoryScanner,
                             CallSiteScanner callSiteScanner,
                             MethodCandidateFactory candidateFactory,
                             EntryPointDetector entryPointDetector,
                             PublicApiDetector publicApiDetector,
                             TestClassifier testClassifier,
                             TestLinkageAnalyzer testLinkageAnalyzer,
                             GeneratedClassFilter generatedClassFilter,
                             Clock clock) {
        this.inventoryScanner = inventoryScanner;
        this.callSiteScanner = callSiteScanner;
        this.candidateFactory = candidateFactory;
        this.entryPointDetector = entryPointDetector;
        this.publicApiDetector = publicApiDetector;
        this.testClassifier = testClassifier;
        this.testLinkageAnalyzer = testLinkageAnalyzer;
        this.generatedClassFilter = generatedClassFilter;
        this.clock = clock;
    }

    /**
     * Scans {@code source} and produces the manifest.
     *
     * @throws IllegalArgumentException if the source yielded no classes at all -- an empty manifest
     *         would make every method in the artifact look unobserved, so it must be an error
     */
    public ScanResult assemble(ClassSource source, String buildSha, String artifact)
            throws IOException {
        Collector collector = new Collector();
        source.accept(collector);

        if (collector.classes.isEmpty()) {
            throw new IllegalArgumentException("no classes found in " + source.description()
                    + "; refusing to emit an empty manifest");
        }

        ClassHierarchy hierarchy = new ClassHierarchy(collector.classes);
        List<ResolvedEdge> resolvedEdges =
                new CallGraphBuilder(hierarchy).build(collector.invocations);

        Set<String> testClassNames = testClassNames(collector.classes);
        List<EntryPoint> entryPoints =
                entryPointDetector.detect(collector.classes, collector.serviceProviders);

        Map<MethodRef, MethodLinkage> linkage = testLinkageAnalyzer.analyse(
                collector.classes, testClassNames, resolvedEdges,
                productionEntryPoints(entryPoints, testClassNames));

        Map<String, List<MethodEntry>> probeIndex = ProbeIndex.assign(candidates(collector.classes));

        List<ClassEntry> classes = new ArrayList<>(collector.classes.size());
        for (ClassModel model : collector.classes) {
            classes.add(toClassEntry(model, probeIndex, linkage, testClassNames));
        }

        List<CallEdge> callEdges = new ArrayList<>(resolvedEdges.size());
        for (ResolvedEdge edge : resolvedEdges) {
            callEdges.add(edge.toCallEdge());
        }

        Manifest manifest = new Manifest(Manifest.SCHEMA_VERSION, buildSha, artifact,
                generatedAt(), classes, entryPoints, callEdges);
        return new ScanResult(manifest, collector.classes.size(), collector.generatedSkipped,
                collector.serviceProviders.size());
    }

    private ClassEntry toClassEntry(ClassModel model,
                                    Map<String, List<MethodEntry>> probeIndex,
                                    Map<MethodRef, MethodLinkage> linkage,
                                    Set<String> testClassNames) {
        List<MethodEntry> assigned = probeIndex.getOrDefault(model.name(), List.of());
        List<MethodEntry> withLinkage = new ArrayList<>(assigned.size());
        for (MethodEntry entry : assigned) {
            MethodLinkage found =
                    linkage.get(new MethodRef(model.name(), entry.name(), entry.desc()));
            withLinkage.add(found == null
                    ? entry
                    : entry.withLinkage(found.sccId(), found.testOnlyReachable()));
        }
        return ClassEntry.of(model.name(), model.sourceFile(), publicApiDetector.isPublicApi(model),
                testClassNames.contains(model.name()), withLinkage);
    }

    private List<MethodCandidate> candidates(Collection<ClassModel> classes) {
        List<MethodCandidate> candidates = new ArrayList<>();
        for (ClassModel model : classes) {
            candidates.addAll(candidateFactory.candidatesOf(model));
        }
        return candidates;
    }

    private Set<String> testClassNames(Collection<ClassModel> classes) {
        Set<String> names = new LinkedHashSet<>();
        for (ClassModel model : classes) {
            if (testClassifier.isTest(model)) {
                names.add(model.name());
            }
        }
        return names;
    }

    /**
     * Entry points on non-test classes. A {@code main} method on a test harness is not evidence that
     * production traffic reaches anything -- that is the whole of C50's point.
     */
    private Set<MethodRef> productionEntryPoints(Collection<EntryPoint> entryPoints,
                                                 Set<String> testClassNames) {
        Set<MethodRef> refs = new LinkedHashSet<>();
        for (EntryPoint entryPoint : entryPoints) {
            if (!testClassNames.contains(entryPoint.className())) {
                refs.add(new MethodRef(entryPoint.className(), entryPoint.method(), entryPoint.desc()));
            }
        }
        return refs;
    }

    private String generatedAt() {
        return DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Single-pass collector: two ASM visitors over one byte array, then the array is released.
     *
     * <p>Runtime-generated classes are filtered here rather than downstream so that they never enter
     * the hierarchy, the graph, or the agent's probe accounting. jacoco#655 showed what happens
     * otherwise: proxy and lambda host classes pollute the inventory and grow agent memory without
     * bound, and they can never match a build-time manifest entry anyway.
     */
    private final class Collector implements ClassSourceVisitor {

        private final List<ClassModel> classes = new ArrayList<>();
        private final List<RawInvocation> invocations = new ArrayList<>();
        private final Set<String> serviceProviders = new TreeSet<>();
        private int generatedSkipped;

        @Override
        public void visitClass(String entryPath, byte[] bytes) {
            ClassModel model = inventoryScanner.scan(entryPath, bytes);
            if (model == null) {
                return;
            }
            if (generatedClassFilter.isGenerated(model.name())) {
                generatedSkipped++;
                return;
            }
            classes.add(model);
            invocations.addAll(callSiteScanner.scan(bytes));
        }

        @Override
        public void visitServiceProviders(String serviceName, List<String> providerClassNames) {
            serviceProviders.addAll(providerClassNames);
        }
    }
}
