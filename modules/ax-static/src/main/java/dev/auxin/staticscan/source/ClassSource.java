package dev.auxin.staticscan.source;

import java.io.Closeable;
import java.io.IOException;

/**
 * Something that yields class files and SPI declarations: a jar, or a directory of compiled classes.
 *
 * <p>WHY this is an interface with two implementations rather than "unzip to a temp dir": a build
 * step that writes hundreds of megabytes to disk to read them straight back is a build step that
 * fails in a constrained CI container. Both implementations stream.
 */
public interface ClassSource extends Closeable {

    /** Walks the source exactly once, handing everything found to {@code visitor}. */
    void accept(ClassSourceVisitor visitor) throws IOException;

    /** Human-readable identification of this source, for error messages. */
    String description();
}
