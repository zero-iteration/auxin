package dev.auxin.staticscan;

import dev.auxin.manifest.ClassEntry;
import dev.auxin.manifest.EntryPoint;
import dev.auxin.manifest.MethodCandidate;
import dev.auxin.manifest.MethodEntry;
import dev.auxin.staticscan.api.PublicApiDetector;
import dev.auxin.staticscan.cli.CliOptions;
import dev.auxin.staticscan.cli.Main;
import dev.auxin.staticscan.cli.UsageException;
import dev.auxin.staticscan.entry.BoundaryCatalog;
import dev.auxin.staticscan.entry.BoundarySignature;
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
import dev.auxin.staticscan.tier2.Tier2Selection;
import dev.auxin.staticscan.tier2.Tier2Selector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface- and superclass-based entry-point detection: the boundary an application declares by
 * <em>implementing a type</em> rather than by writing an annotation.
 *
 * <p>WHY this needs its own fixture rather than an addition to {@link FixtureProject}: the hole this
 * closes is measured in real bytecode. {@code Callable<String>.call()} is compiled as
 * {@code ()Ljava/lang/String;} with a synthetic bridge, an abstract re-declaration and a
 * {@code default} method differ only in one access bit, and a {@code protected doGet} override looks
 * nothing like the annotated public methods the other fixture exercises. Hand-built
 * {@code ClassModel}s would test our beliefs about javac; these are javac's answers.
 *
 * <p>The framework types that are not in the JDK are declared locally, exactly as
 * {@link FixtureProject} declares its annotations: ax-static never loads or resolves a supertype, so
 * a local declaration with the right fully-qualified name exercises the same code path a real
 * servlet container would. {@code com.sun.net.httpserver.HttpHandler} is the real JDK interface,
 * because that is the one the demo artifact uses and it is not in the scan -- which is the case that
 * has to work.
 */
class BoundaryEntryPointTest {

    private static final String HANDLE = "(Lcom/sun/net/httpserver/HttpExchange;)V";
    private static final String DO_GET =
            "(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V";

    @TempDir
    static Path tempDir;

    private static Path classesDir;

    @BeforeAll
    static void compileFixture() throws IOException {
        classesDir = compile(tempDir);
    }

    // ---------------------------------------------------------------- signature parsing

    @Test
    @DisplayName("a boundary is a type, a method name and an exact descriptor")
    void parsesASignature() {
        BoundarySignature signature = BoundarySignature.parse(
                "com.sun.net.httpserver.HttpHandler#handle" + HANDLE);

        assertEquals("com.sun.net.httpserver.HttpHandler", signature.declaringType());
        assertEquals("handle", signature.method());
        assertEquals("HttpHandler", signature.kind(), "the kind is the simple name of the type");
        assertTrue(signature.matches("handle", HANDLE));
    }

    @Test
    @DisplayName("a descriptor mismatch is not a match, so an unrelated 'handle' is left alone")
    void refusesADescriptorMismatch() {
        BoundarySignature signature = BoundarySignature.parse(
                "com.sun.net.httpserver.HttpHandler#handle" + HANDLE);

        assertFalse(signature.matches("handle", "(Ljava/lang/String;)V"), "wrong parameter type");
        assertFalse(signature.matches("handle", "()V"), "wrong arity");
        assertFalse(signature.matches("handle",
                "(Lcom/sun/net/httpserver/HttpExchange;Ljava/lang/String;)V"), "wrong arity");
        assertFalse(signature.matches("handle",
                "(Lcom/sun/net/httpserver/HttpExchange;)Ljava/lang/String;"), "wrong return");
        assertFalse(signature.matches("handleRequest", HANDLE), "wrong name");
    }

    @Test
    @DisplayName("'*' matches one reference type, which is what a generic supertype erases to")
    void matchesAnErasedGenericSignature() {
        BoundarySignature call = BoundarySignature.parse("java.util.concurrent.Callable#call()*");
        assertTrue(call.matches("call", "()Ljava/lang/String;"),
                "Callable<String>.call() is compiled as ()Ljava/lang/String;, and the erased "
                        + "()Ljava/lang/Object; exists only as a bridge we refuse to instrument");
        assertTrue(call.matches("call", "()[Ljava/lang/String;"));
        assertFalse(call.matches("call", "()I"), "a type variable never erases to a primitive");
        assertFalse(call.matches("call", "()V"));
        assertFalse(call.matches("call", "(Ljava/lang/String;)Ljava/lang/String;"));

        BoundarySignature netty = BoundarySignature.parse(
                "io.netty.channel.SimpleChannelInboundHandler#channelRead0"
                        + "(Lio/netty/channel/ChannelHandlerContext;*)V");
        assertTrue(netty.matches("channelRead0",
                "(Lio/netty/channel/ChannelHandlerContext;Lcom/acme/Frame;)V"));
        assertFalse(netty.matches("channelRead0", "(Lio/netty/channel/ChannelHandlerContext;I)V"));
    }

    @Test
    @DisplayName("a malformed boundary is rejected with a message that shows the right shape")
    void rejectsMalformedSignatures() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> BoundarySignature.parse("com.acme.Handler#handle")).getMessage()
                        .contains("<type>#<method>(<parameter descriptors>)<return descriptor>"),
                "a bare Type#method must not be guessed to mean 'any descriptor'");
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("com.acme.Handler"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("com.acme.Handler#a#b()V"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("#handle()V"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("com.acme.Handler#()V"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("com/acme/Handler#handle()V"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("com.acme.Handler#handle(Lcom/acme/Req)V"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("com.acme.Handler#handle(Q)V"));
        assertThrows(IllegalArgumentException.class,
                () -> BoundarySignature.parse("com.acme.Handler#handle()"));
        assertThrows(IllegalArgumentException.class, () -> BoundarySignature.parse(""));
        assertThrows(IllegalArgumentException.class, () -> BoundarySignature.parse(null));
    }

    @Test
    @DisplayName("every built-in boundary parses, and Runnable is not one of them")
    void theDefaultCatalogueIsWellFormedAndExcludesRunnable() {
        for (String spec : BoundaryCatalog.defaultSpecs()) {
            assertNotNull(BoundarySignature.parse(spec).kind(), spec);
        }
        assertFalse(BoundaryCatalog.withDefaults().byDeclaringType().containsKey("java.lang.Runnable"),
                "every thread body and executor task would qualify; it must be opt-in");
        assertTrue(BoundaryCatalog.withDefaults().byDeclaringType()
                        .containsKey("com.sun.net.httpserver.HttpHandler"));
        assertTrue(BoundaryCatalog.of(List.of(BoundaryCatalog.RUNNABLE_RUN)).byDeclaringType()
                        .containsKey("java.lang.Runnable"),
                "and the flag must be able to add it");
    }

    @Test
    @DisplayName("re-declaring a built-in boundary folds into it rather than doubling it")
    void deduplicatesSignatures() {
        String duplicate = "com.sun.net.httpserver.HttpHandler#handle" + HANDLE;
        assertEquals(BoundaryCatalog.withDefaults().signatures().size(),
                BoundaryCatalog.of(List.of(duplicate)).signatures().size());
    }

    // ---------------------------------------------------------------- detection, on real bytecode

    @Test
    @DisplayName("with no configuration at all, every boundary implementation in the fixture is tier-2")
    void selectsBoundaryImplementationsWithNoConfiguration() throws IOException {
        Map<String, String> selected = selected(scan(BoundaryCatalog.withDefaults()));

        assertEquals(Map.of(
                        "com.acme.boundary.PlainHandler#handle" + HANDLE, "entryPoint:HttpHandler",
                        "com.acme.boundary.DeepHandler#handle" + HANDLE, "entryPoint:HttpHandler",
                        "com.acme.boundary.ConcreteHandler#handle" + HANDLE, "entryPoint:HttpHandler",
                        "com.acme.boundary.DefaultingHandler#handle" + HANDLE,
                        "entryPoint:HttpHandler",
                        "com.acme.boundary.OrdersServlet#doGet" + DO_GET, "entryPoint:HttpServlet",
                        "com.acme.boundary.Fetch#call()Ljava/lang/String;", "entryPoint:Callable"),
                selected,
                "no --tier2 pattern was supplied: this is what zero-config auxin measures");
    }

    @Test
    @DisplayName("a direct implementation of a framework interface outside the scan is detected")
    void detectsADirectInterfaceImplementation() throws IOException {
        assertEquals("entryPoint:HttpHandler", reason("com.acme.boundary.PlainHandler", "handle",
                HANDLE),
                "the JDK is not in the scan; the declared interface name is the evidence");
    }

    @Test
    @DisplayName("an implementation reached through another interface is detected")
    void detectsATransitiveInterfaceImplementation() throws IOException {
        assertEquals("entryPoint:HttpHandler",
                reason("com.acme.boundary.DeepHandler", "handle", HANDLE),
                "DeepHandler implements TracingHandler, which extends HttpHandler");
    }

    @Test
    @DisplayName("an implementation reached through an abstract superclass is detected")
    void detectsATransitiveSuperclassImplementation() throws IOException {
        assertEquals("entryPoint:HttpHandler",
                reason("com.acme.boundary.ConcreteHandler", "handle", HANDLE));
    }

    @Test
    @DisplayName("a superclass boundary counts: an HttpServlet subclass's do* override is detected")
    void detectsAnHttpServletOverride() throws IOException {
        assertEquals("entryPoint:HttpServlet",
                reason("com.acme.boundary.OrdersServlet", "doGet", DO_GET),
                "doGet is protected, and access is not a bar -- the container still calls it");
    }

    @Test
    @DisplayName("a same-named overload with a different descriptor is not a boundary")
    void ignoresADescriptorMismatchInAnImplementor() throws IOException {
        ManifestAssembler.ScanResult result = scan(BoundaryCatalog.withDefaults());
        assertNull(result.tier2().reasonFor("com.acme.boundary.PlainHandler", "handle",
                        "(Ljava/lang/String;)V"),
                "PlainHandler.handle(String) is a helper, not the HttpHandler callback");
        assertTrue(result.manifest().entryPoints().stream()
                        .noneMatch(e -> e.desc().equals("(Ljava/lang/String;)V")),
                "and it is not recorded as an entry point either");
    }

    @Test
    @DisplayName("a class that merely has the right method, implementing nothing, is not a boundary")
    void requiresTheSupertype() throws IOException {
        assertNull(scan(BoundaryCatalog.withDefaults()).tier2()
                        .reasonFor("com.acme.boundary.NotAHandler", "handle", HANDLE),
                "the descriptor matches; the type relation does not");
    }

    @Test
    @DisplayName("an abstract declaration is not an entry point: there is no body to time")
    void ignoresAbstractAndInterfaceDeclarations() throws IOException {
        ManifestAssembler.ScanResult result = scan(BoundaryCatalog.withDefaults());
        assertNull(result.tier2().reasonFor("com.acme.boundary.AbstractHandler", "handle", HANDLE));
        assertTrue(result.manifest().entryPoints().stream()
                        .noneMatch(e -> e.className().equals("com.acme.boundary.AbstractHandler")),
                "an abstract override is the signature, not the implementation");
        assertTrue(result.manifest().entryPoints().stream()
                        .noneMatch(e -> e.className().equals("com.acme.boundary.TracingHandler")),
                "and an interface that only re-exports the method declares no body at all");
    }

    @Test
    @DisplayName("the boundary type's own declaration is not an application boundary")
    void ignoresTheDeclaringTypeItself() throws IOException {
        assertNull(scan(BoundaryCatalog.withDefaults()).tier2()
                        .reasonFor("javax.servlet.http.HttpServlet", "doGet", DO_GET),
                "a shaded copy of the framework class is not the application's endpoint");
    }

    @Test
    @DisplayName("a generic boundary matches the real override, and the bridge is not a second one")
    void matchesThroughGenericErasureWithoutTheBridge() throws IOException {
        ManifestAssembler.ScanResult result = scan(BoundaryCatalog.withDefaults());
        assertEquals("entryPoint:Callable",
                reason("com.acme.boundary.Fetch", "call", "()Ljava/lang/String;"));
        assertEquals(1, result.manifest().entryPoints().stream()
                        .filter(e -> e.className().equals("com.acme.boundary.Fetch")).count(),
                "javac emits call()Ljava/lang/Object; as a bridge; it must not be enrolled");
    }

    // ---------------------------------------------------------------- Runnable, and the flag

    @Test
    @DisplayName("Runnable#run is NOT selected by default, and IS with --tier2-boundary")
    void runnableIsOptIn() throws IOException {
        assertNull(scan(BoundaryCatalog.withDefaults()).tier2()
                        .reasonFor("com.acme.boundary.Job", "run", "()V"),
                "auto-selecting every thread body would spend the whole budget");

        assertEquals("entryPoint:Runnable",
                scan(BoundaryCatalog.of(List.of(BoundaryCatalog.RUNNABLE_RUN))).tier2()
                        .reasonFor("com.acme.boundary.Job", "run", "()V"),
                "and an operator who knows Runnable is their boundary must be able to say so");
    }

    @Test
    @DisplayName("the opt-in works through the CLI, and the breakdown names the mechanism")
    void theFlagIsPlumbedThroughTheCli() throws IOException {
        String withoutFlag = cliReport();
        assertTrue(withoutFlag.contains("entryPoint:HttpHandler"), withoutFlag);
        assertTrue(withoutFlag.contains("entryPoint:HttpServlet"), withoutFlag);
        assertFalse(withoutFlag.contains("entryPoint:Runnable"),
                "nothing on the command line asked for Runnable:\n" + withoutFlag);

        String withFlag = cliReport("--tier2-boundary", BoundaryCatalog.RUNNABLE_RUN);
        assertTrue(withFlag.contains("entryPoint:Runnable"), withFlag);
    }

    @Test
    @DisplayName("--tier2-boundary is repeatable and parsed like the other tier-2 options")
    void parsesTheBoundaryOption() {
        CliOptions options = CliOptions.parse(new String[] {
                "--input", "/in", "--build-sha", "sha", "--artifact", "svc", "--output", "/o.json",
                "--tier2-boundary", BoundaryCatalog.RUNNABLE_RUN,
                "--tier2-boundary", "com.acme.rpc.Handler#invoke(Lcom/acme/rpc/Req;)V"});
        assertEquals(List.of(BoundaryCatalog.RUNNABLE_RUN,
                        "com.acme.rpc.Handler#invoke(Lcom/acme/rpc/Req;)V"),
                options.tier2Boundaries());

        assertEquals(List.of(), CliOptions.parse(new String[] {
                        "--input", "/in", "--build-sha", "s", "--artifact", "a",
                        "--output", "/o.json"}).tier2Boundaries(),
                "and the default is the built-in catalogue alone");
    }

    @Test
    @DisplayName("a malformed --tier2-boundary exits 2, before the artifact is even opened")
    void malformedBoundaryIsAUsageError() {
        Path output = tempDir.resolve("never-written.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new Main(Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC))
                .run(new String[] {
                        "--input", classesDir.toString(),
                        "--build-sha", "sha", "--artifact", "svc",
                        "--output", output.toString(),
                        "--tier2-boundary", "com.acme.rpc.Handler#invoke"},
                        print(new ByteArrayOutputStream()), print(err));

        assertEquals(Main.EXIT_USAGE, exit, "same class of failure as a malformed --tier2 pattern");
        assertFalse(Files.exists(output));
        String message = err.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("<type>#<method>(<parameter descriptors>)<return descriptor>"),
                message);
    }

    @Test
    @DisplayName("--help documents the flag and says Runnable is deliberately off")
    void helpDocumentsTheBoundaryOption() {
        String usage = assertThrows(UsageException.class,
                () -> CliOptions.parse(new String[] {"--help"})).getMessage();
        assertTrue(usage.contains("--tier2-boundary"), usage);
        assertTrue(usage.contains(BoundaryCatalog.RUNNABLE_RUN), usage);
        assertTrue(usage.contains("NOT on by default"), usage);
        assertTrue(CliOptions.usage().contains("HttpHandler"),
                "the built-in catalogue must be discoverable without reading the source");
    }

    // ---------------------------------------------------------------- the eligibility bars hold

    @Test
    @DisplayName("a boundary method that cannot be covered dynamically is still refused")
    void honoursTheObservabilityBar() {
        Tier2Selection selection = Tier2Selector.automatic().select(
                List.of(MethodCandidate.builder("com.acme.Handler", "handle", HANDLE)
                        .access("public").dynamicallyObservable(false).build()),
                List.of(new EntryPoint("com.acme.Handler", "handle", HANDLE, "HttpHandler")),
                Set.of());

        assertTrue(selection.isEmpty(),
                "C51 does not stop applying because the entry point was found structurally");
    }

    @Test
    @DisplayName("a boundary implementation on a test class is still refused")
    void honoursTheTestClassBar() {
        Tier2Selection selection = Tier2Selector.automatic().select(
                List.of(MethodCandidate.builder("com.acme.HandlerTest", "handle", HANDLE)
                        .access("public").dynamicallyObservable(true).build()),
                List.of(new EntryPoint("com.acme.HandlerTest", "handle", HANDLE, "HttpHandler")),
                Set.of("com.acme.HandlerTest"));

        assertTrue(selection.isEmpty(), "a test's throughput is not the application's throughput");
    }

    @Test
    @DisplayName("boundary entry points count against the budget and fail the scan at exit 3")
    void countsAgainstTheBudget() {
        Path output = tempDir.resolve("over-budget.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new Main(Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC))
                .run(new String[] {
                        "--input", classesDir.toString(),
                        "--build-sha", "sha", "--artifact", "svc",
                        "--output", output.toString(),
                        "--tier2-max", "2"}, print(new ByteArrayOutputStream()), print(err));

        assertEquals(Main.EXIT_TIER2_BUDGET, exit);
        assertFalse(Files.exists(output), "no manifest may be written for a scan that failed");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("entryPoint:HttpHandler"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("scanning the same artifact twice selects exactly the same set")
    void isReproducible() throws IOException {
        assertEquals(selected(scan(BoundaryCatalog.withDefaults())),
                selected(scan(BoundaryCatalog.withDefaults())));
    }

    // ---------------------------------------------------------------- helpers

    private static String reason(String className, String method, String desc) throws IOException {
        return scan(BoundaryCatalog.withDefaults()).tier2().reasonFor(className, method, desc);
    }

    private static ManifestAssembler.ScanResult scan(BoundaryCatalog boundaries) throws IOException {
        try (ClassSource source = ClassSources.open(classesDir)) {
            return new ManifestAssembler(
                    new InventoryScanner(),
                    new CallSiteScanner(),
                    new MethodCandidateFactory(new DynamicObservability(), new ShortCircuitCatalog()),
                    Tier2Selector.automatic(),
                    new EntryPointDetector(new EntryPointCatalog(), boundaries),
                    new PublicApiDetector(),
                    new TestClassifier(),
                    new TestLinkageAnalyzer(),
                    new GeneratedClassFilter(),
                    Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC))
                    .assemble(source, "sha", "boundary-fixture");
        }
    }

    /** The CLI's own report, so the plumbing is tested and not only the selector. */
    private static String cliReport(String... extraArguments) throws IOException {
        List<String> arguments = new ArrayList<>(List.of(
                "--input", classesDir.toString(),
                "--build-sha", "sha", "--artifact", "svc",
                "--output", tempDir.resolve("cli/manifest.json").toString()));
        arguments.addAll(List.of(extraArguments));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new Main(Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC))
                .run(arguments.toArray(new String[0]), print(out), print(err));
        assertEquals(Main.EXIT_OK, exit, err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The tier-2 methods as {@code Class#nameDesc -> reason}, for whole-set assertions. */
    private static Map<String, String> selected(ManifestAssembler.ScanResult result) {
        Map<String, String> selection = new TreeMap<>();
        Tier2Selection tier2 = result.tier2();
        for (ClassEntry cls : result.manifest().classes()) {
            for (MethodEntry method : cls.methods()) {
                String reason = tier2.reasonFor(cls.name(), method.name(), method.desc());
                if (reason != null) {
                    assertTrue(method.tier2(), cls.name() + '#' + method.nameAndDesc());
                    selection.put(cls.name() + '#' + method.nameAndDesc(), reason);
                }
            }
        }
        return selection;
    }

    private static PrintStream print(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    /**
     * Compiles the boundary fixture. Pinned to {@code --release 17} for the same reason
     * {@link FixtureProject} pins it: ASM refuses class files newer than it knows about, and the
     * bytecode every assertion here reads must be reproducible.
     */
    private static Path compile(Path root) throws IOException {
        Path sourceDir = root.resolve("boundary-src");
        Path classes = root.resolve("boundary-classes");
        Files.createDirectories(classes);

        List<String> arguments = new ArrayList<>(List.of(
                "-g", "-nowarn", "-proc:none", "--release", "17", "-d", classes.toString()));
        for (Map.Entry<String, String> entry : sources().entrySet()) {
            Path file = sourceDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            arguments.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK, not a JRE: no system Java compiler found");
        assertEquals(0, compiler.run(null, null, System.err, arguments.toArray(new String[0])),
                "boundary fixture failed to compile");
        return classes;
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();

        // --- the servlet API, declared locally: only the names reach ax-static.
        sources.put("javax.servlet.http.HttpServletRequest", """
                package javax.servlet.http;

                public interface HttpServletRequest {
                    String getPathInfo();
                }
                """);
        sources.put("javax.servlet.http.HttpServletResponse", """
                package javax.servlet.http;

                public interface HttpServletResponse {
                    void setStatus(int status);
                }
                """);
        sources.put("javax.servlet.http.HttpServlet", """
                package javax.servlet.http;

                public class HttpServlet {
                    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                        if (request != null) {
                            response.setStatus(405);
                        }
                    }
                }
                """);

        // --- the application.
        sources.put("com.acme.boundary.PlainHandler", """
                package com.acme.boundary;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpHandler;

                import java.io.IOException;

                public final class PlainHandler implements HttpHandler {
                    private int seen;

                    @Override
                    public void handle(HttpExchange exchange) throws IOException {
                        if (exchange == null) {
                            throw new IOException("no exchange");
                        }
                        exchange.close();
                    }

                    /** Same name, different descriptor: not the boundary. */
                    public void handle(String note) {
                        if (!note.isEmpty()) {
                            seen++;
                        }
                    }

                    public int seen() {
                        return seen;
                    }
                }
                """);

        sources.put("com.acme.boundary.TracingHandler", """
                package com.acme.boundary;

                import com.sun.net.httpserver.HttpHandler;

                public interface TracingHandler extends HttpHandler {
                }
                """);

        sources.put("com.acme.boundary.DeepHandler", """
                package com.acme.boundary;

                import com.sun.net.httpserver.HttpExchange;

                import java.io.IOException;

                public final class DeepHandler implements TracingHandler {
                    @Override
                    public void handle(HttpExchange exchange) throws IOException {
                        if (exchange != null) {
                            exchange.close();
                        }
                    }
                }
                """);

        sources.put("com.acme.boundary.DefaultingHandler", """
                package com.acme.boundary;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpHandler;

                import java.io.IOException;

                public interface DefaultingHandler extends HttpHandler {
                    @Override
                    default void handle(HttpExchange exchange) throws IOException {
                        if (exchange != null) {
                            exchange.close();
                        }
                    }
                }
                """);

        sources.put("com.acme.boundary.AbstractHandler", """
                package com.acme.boundary;

                import com.sun.net.httpserver.HttpExchange;
                import com.sun.net.httpserver.HttpHandler;

                import java.io.IOException;

                public abstract class AbstractHandler implements HttpHandler {
                    @Override
                    public abstract void handle(HttpExchange exchange) throws IOException;

                    protected final void finish(HttpExchange exchange) throws IOException {
                        if (exchange != null) {
                            exchange.close();
                        }
                    }
                }
                """);

        sources.put("com.acme.boundary.ConcreteHandler", """
                package com.acme.boundary;

                import com.sun.net.httpserver.HttpExchange;

                import java.io.IOException;

                public final class ConcreteHandler extends AbstractHandler {
                    @Override
                    public void handle(HttpExchange exchange) throws IOException {
                        if (exchange != null) {
                            exchange.getResponseHeaders().add("X-Demo", "1");
                        }
                        finish(exchange);
                    }
                }
                """);

        sources.put("com.acme.boundary.NotAHandler", """
                package com.acme.boundary;

                import com.sun.net.httpserver.HttpExchange;

                import java.io.IOException;

                public final class NotAHandler {
                    public void handle(HttpExchange exchange) throws IOException {
                        if (exchange != null) {
                            exchange.close();
                        }
                    }
                }
                """);

        sources.put("com.acme.boundary.OrdersServlet", """
                package com.acme.boundary;

                import javax.servlet.http.HttpServlet;
                import javax.servlet.http.HttpServletRequest;
                import javax.servlet.http.HttpServletResponse;

                public class OrdersServlet extends HttpServlet {
                    @Override
                    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                        if (request.getPathInfo() == null) {
                            response.setStatus(400);
                            return;
                        }
                        response.setStatus(200);
                    }
                }
                """);

        sources.put("com.acme.boundary.Job", """
                package com.acme.boundary;

                public final class Job implements Runnable {
                    private int runs;

                    @Override
                    public void run() {
                        runs++;
                        if (runs > 3) {
                            runs = 0;
                        }
                    }
                }
                """);

        sources.put("com.acme.boundary.Fetch", """
                package com.acme.boundary;

                import java.util.concurrent.Callable;

                public final class Fetch implements Callable<String> {
                    @Override
                    public String call() {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 3; i++) {
                            sb.append(i);
                        }
                        return sb.toString();
                    }
                }
                """);

        return sources;
    }
}
