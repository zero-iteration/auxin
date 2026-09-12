package dev.auxin.staticscan;

import dev.auxin.manifest.Manifest;
import dev.auxin.manifest.ManifestReader;
import dev.auxin.staticscan.cli.CliOptions;
import dev.auxin.staticscan.cli.Main;
import dev.auxin.staticscan.cli.UsageException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CLI contract: the documented invocation works, and every wrong one fails distinguishably. */
class CliTest {

    @TempDir
    static Path tempDir;

    private static FixtureProject fixture;

    @BeforeAll
    static void compileFixture() throws IOException {
        fixture = FixtureProject.compile(tempDir);
    }

    private static Main cli() {
        return new Main(Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("the documented invocation writes a manifest the reader accepts")
    void writesAManifest() throws IOException {
        Path output = tempDir.resolve("out/auxin-manifest.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = cli().run(new String[] {
                "--input", fixture.classesDir().toString(),
                "--build-sha", "abc123def",
                "--artifact", "checkout-service",
                "--output", output.toString()}, print(out), print(err));

        assertEquals(Main.EXIT_OK, exit, err.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(output));

        Manifest manifest;
        try (InputStream in = Files.newInputStream(output)) {
            manifest = ManifestReader.read(in);
        }
        assertEquals("abc123def", manifest.buildSha());
        assertEquals("checkout-service", manifest.artifact());
        assertFalse(manifest.classes().isEmpty());

        String report = out.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("classes"), report);
        assertTrue(report.contains("61% unsound"), "the report must not let the graph look trustworthy");
    }

    @Test
    @DisplayName("a missing required option exits 2, not 1")
    void missingOptionIsAUsageError() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = cli().run(new String[] {"--input", fixture.classesDir().toString()},
                print(new ByteArrayOutputStream()), print(err));
        assertEquals(Main.EXIT_USAGE, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--build-sha"));
    }

    @Test
    @DisplayName("an unknown option exits 2 and names the offending flag")
    void unknownOptionIsAUsageError() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = cli().run(new String[] {"--nope"}, print(new ByteArrayOutputStream()), print(err));
        assertEquals(Main.EXIT_USAGE, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--nope"));
    }

    @Test
    @DisplayName("an unreadable input exits 1, and the manifest is not written")
    void badInputIsAScanFailure() {
        Path output = tempDir.resolve("never-written.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = cli().run(new String[] {
                "--input", tempDir.resolve("does-not-exist").toString(),
                "--build-sha", "sha",
                "--artifact", "artifact",
                "--output", output.toString()}, print(new ByteArrayOutputStream()), print(err));

        assertEquals(Main.EXIT_FAILED, exit);
        assertFalse(Files.exists(output));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("does not exist"));
    }

    @Test
    @DisplayName("a flag with no value is rejected rather than swallowing the next flag")
    void flagWithoutValueIsRejected() {
        UsageException e = assertThrows(UsageException.class,
                () -> CliOptions.parse(new String[] {"--input"}));
        assertTrue(e.getMessage().contains("--input requires a value"), e.getMessage());
    }

    @Test
    @DisplayName("all four options are parsed")
    void parsesEveryOption() {
        CliOptions options = CliOptions.parse(new String[] {
                "--input", "/in", "--build-sha", "deadbeef", "--artifact", "svc", "--output", "/out.json"});
        assertEquals("/in", options.input().toString());
        assertEquals("deadbeef", options.buildSha());
        assertEquals("svc", options.artifact());
        assertEquals("/out.json", options.output().toString());
    }

    // ---------------------------------------------------------------- tier-2 options

    @Test
    @DisplayName("the tier-2 options are parsed, and both pattern flags are repeatable")
    void parsesTheTier2Options() {
        CliOptions options = CliOptions.parse(new String[] {
                "--input", "/in", "--build-sha", "deadbeef", "--artifact", "svc",
                "--output", "/out.json",
                "--tier2", "com.acme.**.*Repository#*",
                "--tier2", "com.acme.pricing.RateSelector#pick",
                "--tier2-exclude", "com.acme.**#health",
                "--tier2-max", "40"});
        assertEquals(List.of("com.acme.**.*Repository#*", "com.acme.pricing.RateSelector#pick"),
                options.tier2Includes());
        assertEquals(List.of("com.acme.**#health"), options.tier2Excludes());
        assertEquals(40, options.tier2Max());
    }

    @Test
    @DisplayName("with no tier-2 flags the budget is the documented default and no pattern is set")
    void tier2OptionsHaveSafeDefaults() {
        CliOptions options = CliOptions.parse(new String[] {
                "--input", "/in", "--build-sha", "sha", "--artifact", "svc", "--output", "/o.json"});
        assertEquals(List.of(), options.tier2Includes());
        assertEquals(List.of(), options.tier2Excludes());
        assertEquals(250, options.tier2Max());
    }

    @Test
    @DisplayName("a malformed pattern is a usage error, before the artifact is even opened")
    void malformedPatternIsAUsageError() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = cli().run(new String[] {
                "--input", fixture.classesDir().toString(),
                "--build-sha", "sha", "--artifact", "svc",
                "--output", tempDir.resolve("never.json").toString(),
                "--tier2", "com.acme.Foo"}, print(new ByteArrayOutputStream()), print(err));
        assertEquals(Main.EXIT_USAGE, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("<class-glob>#<method-glob>"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a --tier2-max that is not a non-negative number is a usage error")
    void badBudgetIsAUsageError() {
        assertTrue(assertThrows(UsageException.class, () -> CliOptions.parse(new String[] {
                        "--input", "/in", "--build-sha", "s", "--artifact", "a",
                        "--output", "/o.json", "--tier2-max", "lots"}))
                        .getMessage().contains("whole number"));
        assertTrue(assertThrows(UsageException.class, () -> CliOptions.parse(new String[] {
                        "--input", "/in", "--build-sha", "s", "--artifact", "a",
                        "--output", "/o.json", "--tier2-max", "-1"}))
                        .getMessage().contains("must not be negative"));
    }

    @Test
    @DisplayName("the report names the tier-2 count, the budget, and the reason breakdown")
    void reportsTheTier2Selection() throws IOException {
        Path output = tempDir.resolve("tier2/auxin-manifest.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = cli().run(new String[] {
                "--input", fixture.classesDir().toString(),
                "--build-sha", "abc123def", "--artifact", "checkout-service",
                "--output", output.toString(),
                "--tier2", "com.acme.shipping.Trivia#realWork"}, print(out), print(err));

        assertEquals(Main.EXIT_OK, exit, err.toString(StandardCharsets.UTF_8));
        String report = out.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("tier-2 methods     5 / 250"), report);
        assertTrue(report.contains("entryPoint:GetMapping"), report);
        assertTrue(report.contains("pattern:com.acme.shipping.Trivia#realWork"), report);
        assertTrue(report.contains("80ns"), "the report must say what tier-2 costs");

        String json = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"tier2Reason\": \"entryPoint:GetMapping\""), json);
        assertTrue(json.contains("\"tier2Reason\": \"pattern:com.acme.shipping.Trivia#realWork\""),
                json);
    }

    @Test
    @DisplayName("a zero selection is reported as such rather than left to be inferred")
    void reportsAnEmptyTier2Selection() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = cli().run(new String[] {
                "--input", fixture.classesDir().toString(),
                "--build-sha", "sha", "--artifact", "svc",
                "--output", tempDir.resolve("none.json").toString(),
                "--tier2-exclude", "**#*"}, print(out), print(new ByteArrayOutputStream()));
        assertEquals(Main.EXIT_OK, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8)
                        .contains("tier-2 methods     0 / 250"
                                + "  -- nothing will report TPS, error rate or latency"),
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a pattern that matched nothing is warned about on stderr, and is not fatal")
    void warnsAboutAnUnmatchedPattern() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = cli().run(new String[] {
                "--input", fixture.classesDir().toString(),
                "--build-sha", "sha", "--artifact", "svc",
                "--output", tempDir.resolve("warn.json").toString(),
                "--tier2", "com.acme.Typo#*"}, print(new ByteArrayOutputStream()), print(err));
        assertEquals(Main.EXIT_OK, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8)
                        .contains("warning: --tier2 'com.acme.Typo#*' matched no eligible method"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a budget overrun exits 3, names the contributors, and writes no manifest")
    void budgetOverrunExitsThree() {
        Path output = tempDir.resolve("over-budget.json");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = cli().run(new String[] {
                "--input", fixture.classesDir().toString(),
                "--build-sha", "sha", "--artifact", "svc",
                "--output", output.toString(),
                "--tier2", "com.acme.**#*",
                "--tier2-max", "3"}, print(new ByteArrayOutputStream()), print(err));

        assertEquals(Main.EXIT_TIER2_BUDGET, exit);
        assertNotEquals(Main.EXIT_OK, exit, "a silent success here would ship unbudgeted latency");
        assertFalse(Files.exists(output), "no manifest may be written for a scan that failed");

        String message = err.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("--tier2-max 3"), message);
        assertTrue(message.contains("pattern:com.acme.**#*"), message);
        assertTrue(message.contains("80ns"), message);
    }

    @Test
    @DisplayName("--help documents the tier-2 options")
    void helpDocumentsTier2() {
        assertTrue(assertThrows(UsageException.class,
                        () -> CliOptions.parse(new String[] {"--help"})).getMessage()
                        .contains("--tier2-max"));
        assertTrue(CliOptions.usage().contains("'*' stays inside one package segment"));
    }

    private static PrintStream print(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }
}
