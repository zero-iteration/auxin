package dev.auxin.staticscan.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads classes and SPI declarations from a directory tree of compiled output.
 *
 * <p>WHY this exists next to {@link JarClassSource}: the common CI shape is to scan
 * {@code target/classes} before the jar is even assembled, and the common local shape is to scan a
 * freshly compiled fixture. Both need the relative entry path preserved, because test-origin
 * detection (C50) reads it.
 */
public final class DirectoryClassSource implements ClassSource {

    private static final String CLASS_SUFFIX = ".class";

    private final Path root;

    public DirectoryClassSource(Path root) {
        this.root = root;
    }

    @Override
    public void accept(ClassSourceVisitor visitor) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        // Deterministic visit order, for the same reason as in JarClassSource.
        files.sort(Path::compareTo);

        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (relative.endsWith(CLASS_SUFFIX)) {
                // The absolute path is handed on: "src/test" and "test-classes" markers usually sit
                // above the compilation root, so a relativised path would have already lost them.
                visitor.visitClass(file.toAbsolutePath().toString(), Files.readAllBytes(file));
            } else if (ServiceFiles.isServiceFile(relative)) {
                try (InputStream in = Files.newInputStream(file)) {
                    visitor.visitServiceProviders(ServiceFiles.serviceNameOf(relative),
                            ServiceFiles.readProviders(in));
                }
            }
        }
    }

    @Override
    public String description() {
        return "directory " + root;
    }

    @Override
    public void close() {
        // Nothing held open.
    }
}
