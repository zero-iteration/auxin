package dev.auxin.staticscan.cli;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The four inputs ax-static needs, parsed and validated.
 *
 * <p>WHY every option is required and there are no defaults: {@code buildSha} and {@code artifact}
 * are two thirds of the manifest's identity triple ({@code (buildSha, className, methodDesc)}, per
 * CONTRACTS section 1). A default value for either would produce a manifest that merges with the
 * wrong build's coverage in the collector -- silently, because the schema hashes would still match.
 * An unset identity must be a failure, not a guess.
 */
public record CliOptions(Path input, String buildSha, String artifact, Path output) {

    private static final String USAGE =
            "usage: java -jar ax-static.jar --input <jar|dir> --build-sha <sha> "
                    + "--artifact <name> --output <manifest.json>";

    /**
     * @throws UsageException on an unknown flag, a missing value, or a missing required option
     */
    public static CliOptions parse(String[] args) {
        String input = null;
        String buildSha = null;
        String artifact = null;
        String output = null;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--input" -> input = valueOf(args, ++i, flag);
                case "--build-sha" -> buildSha = valueOf(args, ++i, flag);
                case "--artifact" -> artifact = valueOf(args, ++i, flag);
                case "--output" -> output = valueOf(args, ++i, flag);
                case "--help", "-h" -> throw new UsageException(USAGE);
                default -> throw new UsageException("unknown option '" + flag + "'\n" + USAGE);
            }
        }

        require(input, "--input");
        require(buildSha, "--build-sha");
        require(artifact, "--artifact");
        require(output, "--output");

        return new CliOptions(Paths.get(input), buildSha, artifact, Paths.get(output));
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

    private static void require(String value, String flag) {
        if (value == null || value.isEmpty()) {
            throw new UsageException("missing required option " + flag + "\n" + USAGE);
        }
    }
}
