package dev.auxin.staticscan;

import dev.auxin.manifest.CallEdge;
import dev.auxin.manifest.ClassEntry;
import dev.auxin.manifest.EdgeSemantics;
import dev.auxin.manifest.EntryPoint;
import dev.auxin.manifest.Manifest;
import dev.auxin.manifest.ManifestReader;
import dev.auxin.manifest.ManifestWriter;
import dev.auxin.manifest.MethodEntry;
import dev.auxin.manifest.Resolution;
import dev.auxin.manifest.SchemaHash;
import dev.auxin.staticscan.api.PublicApiDetector;
import dev.auxin.staticscan.entry.BoundaryCatalog;
import dev.auxin.staticscan.entry.EntryPointCatalog;
import dev.auxin.staticscan.entry.EntryPointDetector;
import dev.auxin.staticscan.graph.CallSiteScanner;
import dev.auxin.staticscan.linkage.TestClassifier;
import dev.auxin.staticscan.linkage.TestLinkageAnalyzer;
import dev.auxin.staticscan.scan.DynamicObservability;
import dev.auxin.staticscan.scan.GeneratedClassFilter;
import dev.auxin.staticscan.scan.InventoryScanner;
import dev.auxin.staticscan.scan.MethodCandidateFactory;
import dev.auxin.staticscan.scan.ShortCircuitCatalog;
import dev.auxin.staticscan.source.ClassSource;
import dev.auxin.staticscan.source.ClassSources;
import dev.auxin.staticscan.tier2.Tier2Selector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: compile a real project, scan it, and assert on the manifest that actually comes out.
 *
 * <p>Every assertion here is about a rule whose failure mode is silent. A shifted probe index, a
 * missed entry point, a {@code testOnlyReachable} that should have been {@code null} -- none of them
 * throw at runtime; they just produce a confidently wrong verdict weeks later.
 */
class ManifestScanEndToEndTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-12T10:00:00Z");

    @TempDir
    static Path tempDir;

    private static FixtureProject fixture;
    private static Manifest manifest;

    @BeforeAll
    static void scanTheFixture() throws IOException {
        fixture = FixtureProject.compile(tempDir);
        manifest = scan(fixture.classesDir());
    }

    private static Manifest scan(Path input) throws IOException {
        try (ClassSource source = ClassSources.open(input)) {
            return assembler().assemble(source, "abc123def", "checkout-service").manifest();
        }
    }

    private static ManifestAssembler assembler() {
        return assembler(Tier2Selector.automatic());
    }

    /** The same graph with a chosen tier-2 policy, so the selection rules can be driven directly. */
    static ManifestAssembler assembler(Tier2Selector tier2Selector) {
        return new ManifestAssembler(
                new InventoryScanner(),
                new CallSiteScanner(),
                new MethodCandidateFactory(new DynamicObservability(), new ShortCircuitCatalog()),
                tier2Selector,
                new EntryPointDetector(new EntryPointCatalog(), BoundaryCatalog.withDefaults()),
                new PublicApiDetector(),
                new TestClassifier(),
                new TestLinkageAnalyzer(),
                new GeneratedClassFilter(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------- manifest shape

    @Test
    @DisplayName("the manifest carries the frozen header fields")
    void writesTheHeader() {
        assertEquals(Manifest.SCHEMA_VERSION, manifest.schemaVersion());
        assertEquals("abc123def", manifest.buildSha());
        assertEquals("checkout-service", manifest.artifact());
        assertEquals("2026-09-12T10:00:00Z", manifest.generatedAt());
    }

    @Test
    @DisplayName("the emitted document round trips through the reader")
    void roundTripsThroughTheContract() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ManifestWriter.write(manifest, out);
        assertEquals(manifest, ManifestReader.parse(out.toString("UTF-8")));
    }

    @Test
    @DisplayName("scanning the same artifact twice produces byte-identical output")
    void isReproducible() throws IOException {
        assertArrayEquals(serialise(scan(fixture.classesDir())), serialise(scan(fixture.classesDir())));
    }

    @Test
    @DisplayName("scanning the jar produces the same classes as scanning the directory")
    void jarAndDirectoryAgree() throws IOException {
        Manifest fromJar = scan(fixture.toJar());
        assertEquals(classNames(manifest), classNames(fromJar));
        assertEquals(manifest.entryPoints(), fromJar.entryPoints());
        assertEquals(manifest.callEdges(), fromJar.callEdges());
    }

    @Test
    @DisplayName("an input with no classes is an error, never an empty manifest")
    void refusesToEmitAnEmptyManifest() throws IOException {
        Path empty = tempDir.resolve("empty-dir");
        java.nio.file.Files.createDirectories(empty);
        try (ClassSource source = ClassSources.open(empty)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> assembler().assemble(source, "sha", "artifact"));
            assertTrue(e.getMessage().contains("refusing to emit an empty manifest"), e.getMessage());
        }
    }

    @Test
    @DisplayName("a class compiled without debug info still scans; line and sourceFile degrade")
    void toleratesMissingDebugInfo() throws IOException {
        Path root = tempDir.resolve("nodebug");
        Path source = root.resolve("src/com/acme/Stripped.java");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.writeString(source, """
                package com.acme;

                public class Stripped {
                    public int twice(int n) {
                        int total = n;
                        total += n;
                        return total;
                    }
                }
                """);
        Path classes = root.resolve("classes");
        java.nio.file.Files.createDirectories(classes);
        int status = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, System.err,
                "-g:none", "-nowarn", "-proc:none", "--release", "17",
                "-d", classes.toString(), source.toString());
        assertEquals(0, status);

        Manifest stripped;
        try (ClassSource classSource = ClassSources.open(classes)) {
            stripped = assembler().assemble(classSource, "sha", "stripped").manifest();
        }
        ClassEntry entry = stripped.classes().get(0);
        assertEquals("com.acme.Stripped", entry.name());
        assertEquals("", entry.sourceFile(), "no SourceFile attribute must not become null");
        assertEquals(MethodEntry.UNKNOWN_LINE,
                entry.methods().stream()
                        .filter(m -> m.name().equals("twice"))
                        .findFirst()
                        .orElseThrow()
                        .line());
    }

    // ---------------------------------------------------------------- probe index

    @Test
    @DisplayName("probe indices follow the lexicographic rule, restarting at 0 in each class")
    void assignsProbeIndicesLexicographically() {
        ClassEntry controller = classOf("com.acme.shipping.RateController");
        assertEquals(List.of(
                        "<init>(Lcom/acme/shipping/RateSource;)V",
                        "describe(Ljava/lang/String;)Lcom/acme/shipping/Rate;",
                        "init()V",
                        "quote(Ljava/lang/String;)Lcom/acme/shipping/Rate;",
                        "refresh()V",
                        "warm(Ljava/lang/String;)V"),
                nameAndDescs(controller));
        for (int i = 0; i < controller.methods().size(); i++) {
            assertEquals(i, controller.methods().get(i).idx());
        }
        assertEquals(6, controller.probeCount());
    }

    @Test
    @DisplayName("abstract, native and <clinit> members get no probe and consume no index")
    void skipsMembersThatCannotCarryAProbe() {
        ClassEntry base = classOf("com.acme.shipping.AbstractBase");
        assertEquals(List.of("<init>()V", "concrete()V"), nameAndDescs(base));
    }

    @Test
    @DisplayName("a compiler-generated bridge method gets no probe")
    void skipsBridgeMethods() {
        ClassEntry comparables = classOf("com.acme.shipping.Comparables");
        assertEquals(List.of("<init>(I)V", "compareTo(Lcom/acme/shipping/Comparables;)I"),
                nameAndDescs(comparables));
        for (MethodEntry method : comparables.methods()) {
            assertFalse(method.synthetic());
        }
    }

    @Test
    @DisplayName("every class carries the schema hash of its own member list")
    void computesSchemaHashes() {
        for (ClassEntry cls : manifest.classes()) {
            assertEquals(SchemaHash.of(cls), cls.schemaHash(), cls.name());
            assertEquals(cls.methods().size(), cls.probeCount(), cls.name());
        }
    }

    @Test
    @DisplayName("source file and first line are recorded so a verdict can be looked at")
    void recordsSourceLocation() {
        ClassEntry controller = classOf("com.acme.shipping.RateController");
        assertEquals("RateController.java", controller.sourceFile());
        assertTrue(methodOf(controller, "quote(Ljava/lang/String;)Lcom/acme/shipping/Rate;").line() > 0);
    }

    // ---------------------------------------------------------------- entry points

    @Test
    @DisplayName("method-level annotations are detected by descriptor, with their kind")
    void detectsMethodLevelEntryPoints() {
        assertTrue(hasEntryPoint("com.acme.shipping.RateController", "quote", "GetMapping"));
        assertTrue(hasEntryPoint("com.acme.shipping.RateController", "refresh", "Scheduled"));
        assertTrue(hasEntryPoint("com.acme.shipping.RateController", "init", "PostConstruct"));
    }

    @Test
    @DisplayName("a type-level @RequestMapping reaches public methods but not private ones")
    void propagatesTypeLevelAnnotationsToPublicMethodsOnly() {
        assertTrue(hasEntryPoint("com.acme.shipping.RateController", "describe", "RequestMapping"));
        assertTrue(hasEntryPoint("com.acme.shipping.RateController", "quote", "RequestMapping"));
        assertFalse(hasEntryPoint("com.acme.shipping.RateController", "warm", "RequestMapping"),
                "a private helper is not an HTTP endpoint under any reading");
        assertFalse(hasEntryPoint("com.acme.shipping.RateController", "<init>", "RequestMapping"));
    }

    @Test
    @DisplayName("public static void main is an entry point")
    void detectsMain() {
        assertTrue(hasEntryPoint("com.acme.shipping.Launcher", "main", "main"));
    }

    @Test
    @DisplayName("a META-INF/services provider is an entry point on its no-arg constructor")
    void detectsServiceLoaderProviders() {
        EntryPoint spi = manifest.entryPoints().stream()
                .filter(e -> "ServiceLoader".equals(e.kind()))
                .findFirst()
                .orElse(null);
        assertNotNull(spi, "the SPI declaration should have produced an entry point");
        assertEquals("com.acme.shipping.DefaultRateSource", spi.className());
        assertEquals("<init>", spi.method());
        assertEquals("()V", spi.desc());
    }

    // ---------------------------------------------------------------- public API

    @Test
    @DisplayName("an internal package is not public API; an ordinary public class is")
    void detectsPublicApiSurface() {
        assertFalse(classOf("com.acme.shipping.internal.CachingRateSource").isPublicApi());
        assertTrue(classOf("com.acme.shipping.Rate").isPublicApi());
    }

    // ---------------------------------------------------------------- C51 and proxy short-circuit

    @Test
    @DisplayName("C51: trivial bodies are marked not dynamically observable")
    void marksTrivialBodiesNotObservable() {
        ClassEntry trivia = classOf("com.acme.shipping.Trivia");
        assertFalse(methodOf(trivia, "empty()V").dynamicallyObservable(), "empty method");
        assertFalse(methodOf(trivia, "constant()I").dynamicallyObservable(), "constant return");
        assertFalse(methodOf(trivia, "getName()Ljava/lang/String;").dynamicallyObservable(),
                "plain field accessor");
        assertTrue(methodOf(trivia, "realWork(I)I").dynamicallyObservable(),
                "a loop is real executable code");

        assertFalse(methodOf(classOf("com.acme.shipping.RateController"), "init()V")
                .dynamicallyObservable(), "an empty lifecycle callback is still an empty body");
        assertFalse(methodOf(classOf("com.acme.shipping.Lambdas"), "rateClass()Ljava/lang/Class;")
                .dynamicallyObservable(), "a class-literal return is two instructions");
    }

    @Test
    @DisplayName("C51: a custom exception's delegating constructor is not dynamically observable")
    void marksDelegatingConstructorsNotObservable() {
        assertFalse(methodOf(classOf("com.acme.shipping.TrivialFailure"),
                "<init>(Ljava/lang/String;)V").dynamicallyObservable());
        assertTrue(methodOf(classOf("com.acme.shipping.Rate"), "<init>(I)V").dynamicallyObservable(),
                "a constructor that assigns a field does real work");
    }

    @Test
    @DisplayName("a @Cacheable method is marked short-circuitable; a plain one is not")
    void marksProxyShortCircuitableMethods() {
        assertTrue(methodOf(classOf("com.acme.shipping.internal.CachingRateSource"),
                "lookup(Ljava/lang/String;)Lcom/acme/shipping/Rate;").shortCircuitable());
        assertFalse(methodOf(classOf("com.acme.shipping.DefaultRateSource"),
                "lookup(Ljava/lang/String;)Lcom/acme/shipping/Rate;").shortCircuitable());
    }

    // ---------------------------------------------------------------- call graph

    @Test
    @DisplayName("invokespecial is exact; a call into the JDK is unresolved, not omitted")
    void labelsResolutionHonestly() {
        String main = "com.acme.shipping.Launcher#main([Ljava/lang/String;)V";
        assertTrue(hasEdge(main, "com.acme.shipping.DefaultRateSource#<init>()V",
                Resolution.EXACT, EdgeSemantics.BLOCKING));
        assertTrue(manifest.callEdges().stream()
                        .anyMatch(e -> e.from().equals(main)
                                && e.to().startsWith("java.io.PrintStream#println")
                                && e.resolution() == Resolution.UNRESOLVED),
                "a virtual call on a type outside the artifact cannot be resolved, and must say so");
    }

    @Test
    @DisplayName("a virtual call on a scanned type fans out to every scanned override, as cha")
    void fansOutVirtualCallsOverTheScannedHierarchy() {
        String quote = "com.acme.shipping.RateController#quote(Ljava/lang/String;)Lcom/acme/shipping/Rate;";
        String lookupDesc = "#lookup(Ljava/lang/String;)Lcom/acme/shipping/Rate;";
        assertTrue(hasEdge(quote, "com.acme.shipping.RateSource" + lookupDesc,
                Resolution.CHA, EdgeSemantics.BLOCKING), "the declared target");
        assertTrue(hasEdge(quote, "com.acme.shipping.DefaultRateSource" + lookupDesc,
                Resolution.CHA, EdgeSemantics.BLOCKING), "an implementation in the artifact");
        assertTrue(hasEdge(quote, "com.acme.shipping.internal.CachingRateSource" + lookupDesc,
                Resolution.CHA, EdgeSemantics.BLOCKING), "the other implementation");
    }

    @Test
    @DisplayName("a lambda resolves through BootstrapMethods to its implementation, exactly")
    void resolvesLambdaBodies() {
        CallEdge lambdaEdge = manifest.callEdges().stream()
                .filter(e -> e.from().startsWith("com.acme.shipping.Lambdas#names")
                        && e.to().contains("lambda$"))
                .findFirst()
                .orElse(null);
        assertNotNull(lambdaEdge, "the lambda body should be reachable from its enclosing method");
        assertEquals(Resolution.EXACT, lambdaEdge.resolution());
        assertEquals(EdgeSemantics.BLOCKING, lambdaEdge.semantics());
    }

    @Test
    @DisplayName("C52: instanceof, .class literals and catch types are emitted as no-op edges")
    void labelsNoOpReferences() {
        assertTrue(hasEdge("com.acme.shipping.Lambdas#isRate(Ljava/lang/Object;)Z",
                "com.acme.shipping.Rate#<type>", Resolution.EXACT, EdgeSemantics.NOOP),
                "an instanceof test constant-folds away with its type");
        assertTrue(hasEdge("com.acme.shipping.Lambdas#rateClass()Ljava/lang/Class;",
                "com.acme.shipping.Rate#<type>", Resolution.EXACT, EdgeSemantics.NOOP),
                "a .class literal does not depend on the type's behaviour");
        assertTrue(hasEdge("com.acme.shipping.Lambdas#guarded(Ljava/lang/Object;)Ljava/lang/String;",
                "java.lang.IllegalStateException#<type>", Resolution.EXACT, EdgeSemantics.NOOP),
                "a catch clause references a type without using it");
    }

    @Test
    @DisplayName("every edge carries both a resolution and a semantics label")
    void everyEdgeIsLabelled() {
        assertFalse(manifest.callEdges().isEmpty());
        for (CallEdge edge : manifest.callEdges()) {
            assertNotNull(edge.resolution(), edge.toString());
            assertNotNull(edge.semantics(), edge.toString());
        }
    }

    // ---------------------------------------------------------------- C50

    @Test
    @DisplayName("test classes are marked, not dropped")
    void marksTestClasses() {
        assertTrue(classOf("com.acme.shipping.RateSelectorTest").isTest());
        assertFalse(classOf("com.acme.shipping.RateController").isTest());
    }

    @Test
    @DisplayName("C50: a helper reached only from a test is testOnlyReachable")
    void detectsTestOnlyReachableCode() {
        assertEquals(Boolean.TRUE,
                methodOf(classOf("com.acme.shipping.TestOnlyHelper"), "help(I)Ljava/lang/String;")
                        .testOnlyReachable());
    }

    @Test
    @DisplayName("C50: a library/test cycle collapses into one component and does not keep itself alive")
    void collapsesLibraryTestCycles() {
        MethodEntry step = methodOf(classOf("com.acme.shipping.CycleHelper"), "step(I)V");
        MethodEntry callback = methodOf(classOf("com.acme.shipping.RateSelectorTest"), "callback(I)V");

        assertEquals(callback.sccId(), step.sccId(),
                "step() and callback() call each other, so Tarjan must put them in one component");
        assertEquals(Boolean.TRUE, step.testOnlyReachable(),
                "a production-looking method whose only callers are inside a test cycle is not alive");
    }

    @Test
    @DisplayName("a method reached from production code is testOnlyReachable=false")
    void marksProductionReachableCode() {
        assertEquals(Boolean.FALSE,
                methodOf(classOf("com.acme.shipping.DefaultRateSource"),
                        "lookup(Ljava/lang/String;)Lcom/acme/shipping/Rate;").testOnlyReachable());
        assertEquals(Boolean.FALSE,
                methodOf(classOf("com.acme.shipping.RateController"),
                        "quote(Ljava/lang/String;)Lcom/acme/shipping/Rate;").testOnlyReachable(),
                "an entry point is reached from outside the artifact");
    }

    @Test
    @DisplayName("C50: an undecidable method is null, never false")
    void reportsUndecidableLinkageAsNull() {
        assertNull(methodOf(classOf("com.acme.shipping.Trivia"), "realWork(I)I").testOnlyReachable(),
                "nothing in the artifact calls it, and no edge is not evidence of no caller");
    }

    @Test
    @DisplayName("every probed method gets a component id")
    void assignsComponentIds() {
        for (ClassEntry cls : manifest.classes()) {
            for (MethodEntry method : cls.methods()) {
                assertTrue(method.sccId() >= 0, cls.name() + '#' + method.nameAndDesc());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] serialise(Manifest manifest) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ManifestWriter.write(manifest, out);
        return out.toByteArray();
    }

    private static List<String> classNames(Manifest manifest) {
        List<String> names = new ArrayList<>();
        for (ClassEntry cls : manifest.classes()) {
            names.add(cls.name());
        }
        return names;
    }

    private static ClassEntry classOf(String name) {
        return manifest.classes().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class entry for " + name
                        + "; scanned " + classNames(manifest)));
    }

    private static MethodEntry methodOf(ClassEntry cls, String nameAndDesc) {
        return cls.methods().stream()
                .filter(m -> m.nameAndDesc().equals(nameAndDesc))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + nameAndDesc + " in " + cls.name()
                        + "; has " + nameAndDescs(cls)));
    }

    private static List<String> nameAndDescs(ClassEntry cls) {
        List<String> out = new ArrayList<>();
        for (MethodEntry m : cls.methods()) {
            out.add(m.nameAndDesc());
        }
        return out;
    }

    private static boolean hasEntryPoint(String className, String method, String kind) {
        return manifest.entryPoints().stream()
                .anyMatch(e -> e.className().equals(className)
                        && e.method().equals(method)
                        && e.kind().equals(kind));
    }

    private static boolean hasEdge(String from, String to, Resolution resolution,
                                   EdgeSemantics semantics) {
        return manifest.callEdges().contains(new CallEdge(from, to, resolution, semantics));
    }
}
