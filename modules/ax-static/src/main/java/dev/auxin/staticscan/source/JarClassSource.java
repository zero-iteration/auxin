package dev.auxin.staticscan.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads classes and SPI declarations from a jar.
 *
 * <p><b>Known limitation, stated rather than hidden:</b> nested archives are not opened. A Spring
 * Boot fat jar keeps application classes under {@code BOOT-INF/classes/} (which this source does
 * read, because those are plain entries) but its dependencies under {@code BOOT-INF/lib/*.jar}
 * (which it does not). PLAN-v2 lists fat-jar support as something that must be tested and claimed,
 * never assumed, so this source does not quietly half-support it.
 */
public final class JarClassSource implements ClassSource {

    private static final String CLASS_SUFFIX = ".class";

    private final Path jar;
    private ZipFile zip;

    public JarClassSource(Path jar) {
        this.jar = jar;
    }

    @Override
    public void accept(ClassSourceVisitor visitor) throws IOException {
        if (zip == null) {
            zip = new ZipFile(jar.toFile());
        }
        Enumeration<? extends ZipEntry> entries = zip.entries();
        // Sorted so that a scan of the same jar visits entries in the same order on every machine.
        // Manifest ordering does not depend on it, but reproducible logs and failure reports do.
        List<ZipEntry> ordered = new ArrayList<>();
        while (entries.hasMoreElements()) {
            ordered.add(entries.nextElement());
        }
        ordered.sort((a, b) -> a.getName().compareTo(b.getName()));

        for (ZipEntry entry : ordered) {
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (name.endsWith(CLASS_SUFFIX)) {
                try (InputStream in = zip.getInputStream(entry)) {
                    visitor.visitClass(name, in.readAllBytes());
                }
            } else if (ServiceFiles.isServiceFile(name)) {
                try (InputStream in = zip.getInputStream(entry)) {
                    visitor.visitServiceProviders(ServiceFiles.serviceNameOf(name),
                            ServiceFiles.readProviders(in));
                }
            }
        }
    }

    @Override
    public String description() {
        return "jar " + jar;
    }

    @Override
    public void close() throws IOException {
        if (zip != null) {
            zip.close();
            zip = null;
        }
    }
}
