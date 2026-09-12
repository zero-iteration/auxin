package dev.auxin.staticscan.cli;

import dev.auxin.staticscan.entry.BoundaryCatalog;
import dev.auxin.staticscan.entry.BoundarySignature;
import dev.auxin.staticscan.tier2.MethodPattern;
import dev.auxin.staticscan.tier2.Tier2Selector;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The inputs ax-static needs, parsed and validated.
 *
 * <p>WHY the four identity options are required and have no defaults: {@code buildSha} and
 * {@code artifact} are two thirds of the manifest's identity triple
 * ({@code (buildSha, className, methodDesc)}, per CONTRACTS section 1). A default value for either
 * would produce a manifest that merges with the wrong build's coverage in the collector -- silently,
 * because the schema hashes would still match. An unset identity must be a failure, not a guess.
 *
 * <p>WHY the tier-2 options, by contrast, all have defaults: the automatic half of tier-2 selection
 * is supposed to need no configuration at all. Every entry point is timed -- annotated ones and
 * implementations of a {@link BoundaryCatalog} type alike -- bounded by
 * {@link Tier2Selector#DEFAULT_MAX}, and the options are there for what an operator chooses to add
 * on top: {@code --tier2} for internals, {@code --tier2-boundary} for a framework boundary the
 * built-in catalogue does not know (including {@link BoundaryCatalog#RUNNABLE_RUN}, which is known
 * but deliberately off by default).
 *
 * <p>Patterns are compiled here, at parse time, so a malformed glob is a usage error (exit 2) rather
 * than a scan failure (exit 1) -- and so it is reported before the artifact is read rather than
 * after.
 */
public record CliOptions(Path input,
                         String buildSha,
                         String artifact,
                         Path output,
                         List<String> tier2Includes,
                         List<String> tier2Excludes,
                         List<String> tier2Boundaries,
                         int tier2Max) {

    private static final String USAGE =
            "usage: java -jar ax-static.jar --input <jar|dir> --build-sha <sha> "
                    + "--artifact <name> --output <manifest.json>\n"
                    + "             [--tier2 <pattern>]... [--tier2-exclude <pattern>]... "
                    + "[--tier2-boundary <sig>]...\n"
                    + "             [--tier2-max <n>]\n"
                    + "\n"
                    + "tier-2 (TPS, error rate, p50/p90/p99) is enabled automatically on every\n"
                    + "detected entry point -- annotated ones (@GetMapping, @KafkaListener, ...) and\n"
                    + "implementations of a known framework boundary type (HttpHandler, Servlet,\n"
                    + "Filter, HttpServlet, MessageListener, Netty/Undertow handlers, Callable).\n"
                    + "The options add or remove methods on top of that:\n"
                    + "  --tier2 <pattern>          also time methods matching "
                    + "<class-glob>#<method-glob>;\n"
                    + "                             '*' stays inside one package segment, '**' "
                    + "crosses them.\n"
                    + "                             Repeatable. e.g. 'com.acme.**.*Repository#*'\n"
                    + "  --tier2-exclude <pattern>  remove matching methods, applied after the\n"
                    + "                             includes and after the automatic entry points.\n"
                    + "                             Repeatable. '**#*' disables tier-2 entirely.\n"
                    + "  --tier2-boundary <sig>     add a framework boundary type to the catalogue:\n"
                    + "                             <type>#<method>(<params>)<return>, in JVM\n"
                    + "                             descriptors. Any class in the artifact that\n"
                    + "                             implements or extends <type> and declares that\n"
                    + "                             exact signature becomes an entry point. '*'\n"
                    + "                             stands for one reference type, for a signature\n"
                    + "                             erased from a generic supertype. Repeatable.\n"
                    + "                             e.g. 'com.acme.rpc.Handler"
                    + "#invoke(Lcom/acme/rpc/Req;)V'\n"
                    + "                             NOTE: " + BoundaryCatalog.RUNNABLE_RUN
                    + " is known but is\n"
                    + "                             NOT on by default -- every thread body, executor\n"
                    + "                             task and desugared block would qualify, which\n"
                    + "                             would spend the whole budget and put ~80ns on\n"
                    + "                             paths nobody asked about. Pass\n"
                    + "                             '--tier2-boundary " + BoundaryCatalog.RUNNABLE_RUN
                    + "' to opt in.\n"
                    + "  --tier2-max <n>            fail the scan if more than n methods are "
                    + "selected\n"
                    + "                             (default " + Tier2Selector.DEFAULT_MAX
                    + "). Tier-2 costs ~80ns per call per method.";

    public CliOptions {
        tier2Includes = List.copyOf(tier2Includes);
        tier2Excludes = List.copyOf(tier2Excludes);
        tier2Boundaries = List.copyOf(tier2Boundaries);
    }

    /**
     * @throws UsageException on an unknown flag, a missing value, a missing required option, a
     *         malformed tier-2 pattern, or a non-numeric or negative {@code --tier2-max}
     */
    public static CliOptions parse(String[] args) {
        String input = null;
        String buildSha = null;
        String artifact = null;
        String output = null;
        List<String> tier2Includes = new ArrayList<>();
        List<String> tier2Excludes = new ArrayList<>();
        List<String> tier2Boundaries = new ArrayList<>();
        int tier2Max = Tier2Selector.DEFAULT_MAX;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--input" -> input = valueOf(args, ++i, flag);
                case "--build-sha" -> buildSha = valueOf(args, ++i, flag);
                case "--artifact" -> artifact = valueOf(args, ++i, flag);
                case "--output" -> output = valueOf(args, ++i, flag);
                case "--tier2" -> tier2Includes.add(pattern(valueOf(args, ++i, flag)));
                case "--tier2-exclude" -> tier2Excludes.add(pattern(valueOf(args, ++i, flag)));
                case "--tier2-boundary" -> tier2Boundaries.add(boundary(valueOf(args, ++i, flag)));
                case "--tier2-max" -> tier2Max = budget(valueOf(args, ++i, flag));
                case "--help", "-h" -> throw new UsageException(USAGE);
                default -> throw new UsageException("unknown option '" + flag + "'\n" + USAGE);
            }
        }

        require(input, "--input");
        require(buildSha, "--build-sha");
        require(artifact, "--artifact");
        require(output, "--output");

        return new CliOptions(Paths.get(input), buildSha, artifact, Paths.get(output),
                tier2Includes, tier2Excludes, tier2Boundaries, tier2Max);
    }

    public static String usage() {
        return USAGE;
    }

    private static String valueOf(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new UsageException(flag + " requires a value\n" + USAGE);
        }
        return args[index];
    }

    /** Compiles and discards, purely to reject a malformed glob at parse time. */
    private static String pattern(String value) {
        try {
            MethodPattern.compile(value);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + "\n" + USAGE);
        }
        return value;
    }

    /**
     * Same again for a boundary signature: parsed and discarded so that a malformed one is a usage
     * error (exit 2) rather than a boundary that quietly matches nothing.
     */
    private static String boundary(String value) {
        try {
            BoundarySignature.parse(value);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + "\n" + USAGE);
        }
        return value;
    }

    private static int budget(String value) {
        int max;
        try {
            max = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new UsageException("--tier2-max must be a whole number, got '" + value + "'\n"
                    + USAGE);
        }
        if (max < 0) {
            throw new UsageException("--tier2-max must not be negative, got " + max + "\n" + USAGE);
        }
        return max;
    }

    private static void require(String value, String flag) {
        if (value == null || value.isEmpty()) {
            throw new UsageException("missing required option " + flag + "\n" + USAGE);
        }
    }
}
