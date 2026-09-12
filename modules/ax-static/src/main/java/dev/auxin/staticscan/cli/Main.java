package dev.auxin.staticscan.cli;

import dev.auxin.manifest.ManifestWriter;
import dev.auxin.staticscan.ManifestAssembler;
import dev.auxin.staticscan.api.PublicApiDetector;
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Command-line entry point: scan an artifact, write {@code auxin-manifest.json}.
 *
 * <p>WHY the object graph is wired here by hand and nowhere else: every collaborator takes its
 * dependencies through its constructor, so this is the only place that needs to know the
 * composition. Tests build the same graph with whatever they want substituted -- a fixed
 * {@link Clock}, most usefully -- without a framework and without any static state to reset.
 *
 * <p>Exit codes are distinct on purpose so a pipeline can branch on them: {@code 0} success,
 * {@code 1} the scan failed, {@code 2} the command line was wrong.
 */
public final class Main {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    private final Clock clock;

    public Main(Clock clock) {
        this.clock = clock;
    }

    public static void main(String[] args) {
        System.exit(new Main(Clock.systemUTC()).run(args, System.out, System.err));
    }

    /** Runs one scan. Returns the process exit code; never throws. */
    public int run(String[] args, PrintStream out, PrintStream err) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (UsageException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }

        try (ClassSource source = ClassSources.open(options.input())) {
            ManifestAssembler.ScanResult result =
                    assembler().assemble(source, options.buildSha(), options.artifact());
            writeTo(options.output(), result);
            report(out, options, result);
            return EXIT_OK;
        } catch (IOException | RuntimeException e) {
            err.println("ax-static: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return EXIT_FAILED;
        }
    }

    private ManifestAssembler assembler() {
        return new ManifestAssembler(
                new InventoryScanner(),
                new CallSiteScanner(),
                new MethodCandidateFactory(new DynamicObservability(), new ShortCircuitCatalog()),
                new EntryPointDetector(new EntryPointCatalog()),
                new PublicApiDetector(),
                new TestClassifier(),
                new TestLinkageAnalyzer(),
                new GeneratedClassFilter(),
                clock);
    }

    private void writeTo(Path output, ManifestAssembler.ScanResult result) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(output)) {
            ManifestWriter.write(result.manifest(), out);
        }
    }

    /**
     * Prints the counters an operator needs to decide whether to trust the manifest. The skipped and
     * unresolved counts are reported unconditionally: PLAN-v2 requires skip and failure counters to
     * ship from day one rather than being added after the first silent failure.
     */
    private void report(PrintStream out, CliOptions options, ManifestAssembler.ScanResult result) {
        out.println("ax-static: wrote " + options.output());
        out.println("  artifact           " + result.manifest().artifact()
                + " @ " + result.manifest().buildSha());
        out.println("  classes            " + result.classesScanned()
                + " (" + result.generatedClassesSkipped() + " runtime-generated skipped)");
        out.println("  entry points       " + result.manifest().entryPoints().size()
                + " (" + result.serviceProvidersDeclared() + " from META-INF/services)");
        out.println("  call edges         " + result.manifest().callEdges().size()
                + "  -- corroborating signal only; this graph is known to be ~61% unsound");
    }
}
