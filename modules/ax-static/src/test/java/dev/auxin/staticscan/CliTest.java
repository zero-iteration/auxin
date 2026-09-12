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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static PrintStream print(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }
}
