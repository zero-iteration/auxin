package dev.auxin.staticscan;

import dev.auxin.manifest.ClassEntry;
import dev.auxin.manifest.Manifest;
import dev.auxin.manifest.ManifestReader;
import dev.auxin.manifest.ManifestWriter;
import dev.auxin.manifest.MethodEntry;
import dev.auxin.staticscan.source.ClassSource;
import dev.auxin.staticscan.source.ClassSources;
import dev.auxin.staticscan.tier2.Tier2BudgetExceededException;
import dev.auxin.staticscan.tier2.Tier2Selection;
import dev.auxin.staticscan.tier2.Tier2Selector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-2 selection against real javac output, and the manifest that comes out of it.
 *
 * <p>{@link Tier2SelectionTest} pins the rules as pure functions; this pins that they fire on the
 * bytecode javac actually emits -- that the bridge method javac synthesises for
 * {@code Comparable}, the lambda body it lifts into a synthetic method, and the
 * {@code <clinit>} it generates for a {@code static final} field are all excluded in practice and
 * not only in principle.
 */
class Tier2ManifestTest {

    @TempDir
    static Path tempDir;

    private static FixtureProject fixture;

    @BeforeAll
    static void compileFixture() throws IOException {
        fixture = FixtureProject.compile(tempDir);
    }

    // ---------------------------------------------------------------- automatic selection

    @Test
    @DisplayName("with no flags at all, every eligible entry point in the fixture is tier-2")
    void entryPointsAreSelectedWithNoConfiguration() throws IOException {
        ManifestAssembler.ScanResult result = scan(Tier2Selector.automatic());

        assertEquals(Map.of(
                        "com.acme.shipping.Launcher#main([Ljava/lang/String;)V", "entryPoint:main",
                        "com.acme.shipping.RateController#describe(Ljava/lang/String;)"
                                + "Lcom/acme/shipping/Rate;", "entryPoint:RequestMapping",
                        "com.acme.shipping.RateController#quote(Ljava/lang/String;)"
                                + "Lcom/acme/shipping/Rate;", "entryPoint:GetMapping",
                        "com.acme.shipping.RateController#refresh()V", "entryPoint:Scheduled"),
                selected(result));
    }

    @Test
    @DisplayName("an entry point whose body cannot be covered dynamically is not selected")
    void doesNotSelectATrivialEntryPoint() throws IOException {
        ManifestAssembler.ScanResult result = scan(Tier2Selector.automatic());
        assertTrue(result.manifest().entryPoints().stream()
                        .anyMatch(e -> e.method().equals("init")),
                "@PostConstruct init() must still be an entry point");
        assertNull(result.tier2().reasonFor("com.acme.shipping.RateController", "init", "()V"),
                "an empty lifecycle callback has nothing to time");
        assertFalse(methodOf(result.manifest(), "com.acme.shipping.RateController", "init()V").tier2());
    }

    @Test
    @DisplayName("the ServiceLoader provider's delegating constructor is not selected either")
    void doesNotSelectADelegatingConstructor() throws IOException {
        ManifestAssembler.ScanResult result = scan(Tier2Selector.automatic());
        assertNull(result.tier2()
                .reasonFor("com.acme.shipping.DefaultRateSource", "<init>", "()V"));
    }

    // ---------------------------------------------------------------- explicit selection

    @Test
    @DisplayName("'**' crosses package boundaries on real class names and '*' does not")
    void globsMatchRealPackages() throws IOException {
        assertTrue(selected(scan(selector("com.acme.**.CachingRateSource#lookup")))
                        .containsKey("com.acme.shipping.internal.CachingRateSource"
                                + "#lookup(Ljava/lang/String;)Lcom/acme/shipping/Rate;"),
                "'**' must reach com.acme.shipping.internal from com.acme");
        assertFalse(selected(scan(selector("com.acme.*.CachingRateSource#lookup")))
                        .containsKey("com.acme.shipping.internal.CachingRateSource"
                                + "#lookup(Ljava/lang/String;)Lcom/acme/shipping/Rate;"),
                "'*' must not cross 'shipping.internal'");
    }

    @Test
    @DisplayName("a @Cacheable method can be timed: shortCircuitable bars a verdict, not a probe")
    void selectsAShortCircuitableMethod() throws IOException {
        ManifestAssembler.ScanResult result = scan(selector("com.acme.**.CachingRateSource#lookup"));
        MethodEntry lookup = methodOf(result.manifest(),
                "com.acme.shipping.internal.CachingRateSource",
                "lookup(Ljava/lang/String;)Lcom/acme/shipping/Rate;");
        assertTrue(lookup.shortCircuitable(), "the fixture's @Cacheable must still be recorded");
        assertTrue(lookup.tier2());
    }

    @Test
    @DisplayName("javac's bridge method and lambda body are not selected; the real method is")
    void doesNotSelectCompilerGeneratedMembers() throws IOException {
        Map<String, String> selection = selected(scan(selector(
                "com.acme.shipping.Comparables#*", "com.acme.shipping.Lambdas#*")));

        assertTrue(selection.containsKey(
                "com.acme.shipping.Comparables#compareTo(Lcom/acme/shipping/Comparables;)I"));
        assertFalse(selection.containsKey(
                        "com.acme.shipping.Comparables#compareTo(Ljava/lang/Object;)I"),
                "the ACC_BRIDGE overload javac generated is not an author-written method");
        for (String ref : selection.keySet()) {
            assertFalse(ref.contains("lambda$"), "a synthetic lambda body is never tier-2: " + ref);
        }
    }

    @Test
    @DisplayName("the generated <clinit> is not selected even by an exact pattern naming it")
    void doesNotSelectClassInitializers() throws IOException {
        ManifestAssembler.ScanResult result =
                scan(selector("com.acme.shipping.AbstractBase#<clinit>"));
        assertFalse(result.tier2().isTier2("com.acme.shipping.AbstractBase", "<clinit>", "()V"),
                "PLAN-v2: never <clinit>, full stop");
        assertEquals(List.of("com.acme.shipping.AbstractBase#<clinit>"),
                result.tier2().unmatchedIncludes(),
                "and the pattern must be reported as having matched nothing");
    }

    @Test
    @DisplayName("a test class is not selected even by a pattern that names it exactly")
    void doesNotSelectTestClasses() throws IOException {
        ManifestAssembler.ScanResult result = scan(selector("com.acme.shipping.RateSelectorTest#*"));
        assertTrue(result.manifest().classes().stream()
                        .anyMatch(c -> c.name().equals("com.acme.shipping.RateSelectorTest")
                                && c.isTest()),
                "the fixture's test class must be marked, or this test proves nothing");
        for (String ref : selected(result).keySet()) {
            assertFalse(ref.startsWith("com.acme.shipping.RateSelectorTest#"), ref);
        }
        assertEquals(List.of("com.acme.shipping.RateSelectorTest#*"),
                result.tier2().unmatchedIncludes());
    }

    @Test
    @DisplayName("an exclude drops an automatically selected entry point")
    void excludeDropsAnEntryPoint() throws IOException {
        ManifestAssembler.ScanResult result = scan(Tier2Selector.of(
                List.of(), List.of("com.acme.shipping.RateController#*"), 250));
        assertEquals(Map.of("com.acme.shipping.Launcher#main([Ljava/lang/String;)V",
                "entryPoint:main"), selected(result));
    }

    // ---------------------------------------------------------------- budget

    @Test
    @DisplayName("the whole fixture over a tiny budget fails the scan rather than truncating")
    void budgetFailsTheScan() throws IOException {
        try (ClassSource source = ClassSources.open(fixture.classesDir())) {
            ManifestAssembler assembler = ManifestScanEndToEndTest.assembler(
                    Tier2Selector.of(List.of("**#*"), List.of(), 3));
            Tier2BudgetExceededException e = assertThrows(Tier2BudgetExceededException.class,
                    () -> assembler.assemble(source, "sha", "artifact"));
            assertEquals(3, e.max());
            assertTrue(e.selected() > 3);
            assertTrue(e.getMessage().contains("pattern:**#*"), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the manifest document

    @Test
    @DisplayName("tier2Reason is emitted for a tier-2 method and absent for every other")
    void writesTier2Reason() throws IOException {
        ManifestAssembler.ScanResult result = scan(selector("com.acme.shipping.Trivia#realWork"));
        String json = json(result);

        assertTrue(json.contains("\"tier2Reason\": \"pattern:com.acme.shipping.Trivia#realWork\""),
                json);
        assertTrue(json.contains("\"tier2Reason\": \"entryPoint:GetMapping\""), json);
        assertEquals(result.tier2().count(), count(json, "\"tier2Reason\""),
                "exactly one reason per selected method, and none for anything else");
        assertEquals(result.tier2().count(), count(json, "\"tier2\": true"),
                "the boolean and the reason must agree");
    }

    @Test
    @DisplayName("the annotated document still round trips through the frozen reader")
    void roundTripsThroughTheContract() throws IOException {
        ManifestAssembler.ScanResult result = scan(Tier2Selector.automatic());
        Manifest reread = ManifestReader.parse(json(result));
        assertEquals(result.manifest(), reread,
                "tier2Reason is an additive key; ManifestReader documents that it ignores unknown "
                        + "keys, so adding it must not change the typed model that comes back");
    }

    @Test
    @DisplayName("the annotated writer is the frozen writer plus tier2Reason lines, byte for byte")
    void doesNotDriftFromTheFrozenWriter() throws IOException {
        ManifestAssembler.ScanResult result = scan(selector("com.acme.shipping.Trivia#realWork"));

        ByteArrayOutputStream frozen = new ByteArrayOutputStream();
        ManifestWriter.write(result.manifest(), frozen);

        // Every tier2Reason line sits between two other fields, so it always carries a trailing
        // comma and deleting the whole line leaves valid, unchanged output.
        List<String> stripped = new ArrayList<>();
        for (String line : json(result).split("\n", -1)) {
            if (!line.trim().startsWith("\"tier2Reason\"")) {
                stripped.add(line);
            }
        }
        assertEquals(frozen.toString(StandardCharsets.UTF_8), String.join("\n", stripped),
                "ManifestJsonWriter duplicates ManifestWriter's field order; if the frozen writer "
                        + "changes, this test is how the copy finds out");
    }

    @Test
    @DisplayName("scanning twice with the same flags produces byte-identical annotated output")
    void isReproducible() throws IOException {
        assertEquals(json(scan(selector("com.acme.**#*o*"))),
                json(scan(selector("com.acme.**#*o*"))));
    }

    // ---------------------------------------------------------------- helpers

    private static ManifestAssembler.ScanResult scan(Tier2Selector tier2Selector) throws IOException {
        try (ClassSource source = ClassSources.open(fixture.classesDir())) {
            return ManifestScanEndToEndTest.assembler(tier2Selector)
                    .assemble(source, "abc123def", "checkout-service");
        }
    }

    private static Tier2Selector selector(String... includes) {
        return Tier2Selector.of(List.of(includes), List.of(), 250);
    }

    /** The tier-2 methods as {@code Class#nameDesc -> reason}, for readable whole-set assertions. */
    private static Map<String, String> selected(ManifestAssembler.ScanResult result) {
        Map<String, String> selection = new TreeMap<>();
        Tier2Selection tier2 = result.tier2();
        for (ClassEntry cls : result.manifest().classes()) {
            for (MethodEntry method : cls.methods()) {
                String reason = tier2.reasonFor(cls.name(), method.name(), method.desc());
                if (reason != null) {
                    assertTrue(method.tier2(), cls.name() + '#' + method.nameAndDesc());
                    selection.put(cls.name() + '#' + method.nameAndDesc(), reason);
                } else {
                    assertFalse(method.tier2(), cls.name() + '#' + method.nameAndDesc());
                }
            }
        }
        return selection;
    }

    private static String json(ManifestAssembler.ScanResult result) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ManifestJsonWriter.write(result.manifest(), result.tier2(), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static MethodEntry methodOf(Manifest manifest, String className, String nameAndDesc) {
        return manifest.classes().stream()
                .filter(c -> c.name().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class " + className))
                .methods().stream()
                .filter(m -> m.nameAndDesc().equals(nameAndDesc))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + nameAndDesc + " in " + className));
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
