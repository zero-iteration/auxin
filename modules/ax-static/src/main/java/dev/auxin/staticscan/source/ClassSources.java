package dev.auxin.staticscan.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Chooses the right {@link ClassSource} for a path.
 *
 * <p>WHY the choice is explicit and fails loudly: silently treating an unreadable or wrongly-typed
 * input as "empty" would produce a valid, empty manifest, and an empty manifest makes every method
 * in the artifact look unobserved. A scan that found nothing must be an error, not a result.
 */
public final class ClassSources {

    private ClassSources() {
    }

    /**
     * @throws IllegalArgumentException if the path does not exist, or is a file that is not an
     *         archive
     */
    public static ClassSource open(Path input) {
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("input does not exist: " + input);
        }
        if (Files.isDirectory(input)) {
            return new DirectoryClassSource(input);
        }
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".war")) {
            return new JarClassSource(input);
        }
        throw new IllegalArgumentException(
                "input must be a directory or a .jar/.war/.zip archive, got: " + input);
    }
}
