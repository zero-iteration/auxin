package dev.auxin.staticscan.cli;

import dev.auxin.staticscan.ManifestAssembler;
import dev.auxin.staticscan.ManifestJsonWriter;
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
import dev.auxin.staticscan.tier2.Tier2BudgetExceededException;
import dev.auxin.staticscan.tier2.Tier2Selection;
import dev.auxin.staticscan.tier2.Tier2Selector;

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
 * {@code 1} the scan failed, {@code 2} the command line was wrong, {@code 3} tier-2 selection
 * exceeded its budget. {@code 3} is separated from {@code 1} because it is the one failure whose
 * remedy is a deliberate decision about cost rather than a bug to fix -- a pipeline can surface it
 * to a human instead of retrying it.
 */
public final class Main {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_TIER2_BUDGET = 3;

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
                    assembler(options).assemble(source, options.buildSha(), options.artifact());
            writeTo(options.output(), result);
            report(out, options, result);
            warn(err, result.tier2());
            return EXIT_OK;
        } catch (Tier2BudgetExceededException e) {
            // Deliberately before the general handler: no manifest has been written, and the
            // remedy is a decision about latency cost, not a retry.
            err.println("ax-static: " + e.getMessage());
            return EXIT_TIER2_BUDGET;
        } catch (IOException | RuntimeException e) {
            err.println("ax-static: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return EXIT_FAILED;
        }
    }

    private ManifestAssembler assembler(CliOptions options) {
        return new ManifestAssembler(
                new InventoryScanner(),
                new CallSiteScanner(),
                new MethodCandidateFactory(new DynamicObservability(), new ShortCircuitCatalog()),
                Tier2Selector.of(options.tier2Includes(), options.tier2Excludes(),
                        options.tier2Max()),
                new EntryPointDetector(new EntryPointCatalog(),
                        BoundaryCatalog.of(options.tier2Boundaries())),
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
            ManifestJsonWriter.write(result.manifest(), result.tier2(), out);
        }
    }

    /**
     * Prints the counters an operator needs to decide whether to trust the manifest. The skipped and
     * unresolved counts are reported unconditionally: PLAN-v2 requires skip and failure counters to
     * ship from day one rather than being added after the first silent failure.
     *
     * <p>The tier-2 block is printed whether or not anything was selected, and always against the
     * budget. Under SCOPE-v3 this scan is the only source of TPS, error rate and latency that will
     * exist for the artifact, so "how many methods will report a rate, and which" is not a detail --
     * and a zero there is the single most important thing this report can say.
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

        Tier2Selection tier2 = result.tier2();
        out.println("  tier-2 methods     " + tier2.count() + " / " + options.tier2Max()
                + (tier2.isEmpty()
                        ? "  -- nothing will report TPS, error rate or latency"
                        : "  -- TPS, error rate, p50/p90/p99; ~80ns per call each"));
        for (String line : tier2.breakdownLines()) {
            out.println("      " + line);
        }
    }

    /**
     * A pattern that matched nothing is reported on stderr. It is not fatal -- one pattern list is
     * legitimately shared across several artifacts -- but it must not be silent, because a typo
     * produces exactly the manifest that not passing the flag would.
     */
    private void warn(PrintStream err, Tier2Selection tier2) {
        for (String pattern : tier2.unmatchedIncludes()) {
            err.println("ax-static: warning: --tier2 '" + pattern
                    + "' matched no eligible method in this artifact");
        }
        for (String pattern : tier2.unmatchedExcludes()) {
            err.println("ax-static: warning: --tier2-exclude '" + pattern
                    + "' removed nothing from the tier-2 selection");
        }
    }
}
