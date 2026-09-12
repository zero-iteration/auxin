package dev.auxin.staticscan.source;

import java.util.List;

/**
 * Receives everything a {@link ClassSource} finds, in one pass.
 *
 * <p>WHY a visitor rather than returning a collection of class files: a service jar can hold tens of
 * thousands of classes, and holding every {@code byte[]} at once is a needless multi-hundred-megabyte
 * peak in a build step. The visitor lets the caller run both ASM passes over a byte array and then
 * drop it.
 */
public interface ClassSourceVisitor {

    /**
     * @param entryPath jar entry name or file path, used only for test-origin detection
     * @param bytes     the class file; valid only for the duration of the call
     */
    void visitClass(String entryPath, byte[] bytes);

    /**
     * A {@code META-INF/services} declaration.
     *
     * <p>These matter because a provider named in such a file is instantiated reflectively by
     * {@link java.util.ServiceLoader} with no caller anywhere in the bytecode -- exactly the shape
     * that makes a static graph declare live code dead.
     *
     * @param serviceName          the fully-qualified service interface
     * @param providerClassNames   the declared implementations, comments and blanks already removed
     */
    void visitServiceProviders(String serviceName, List<String> providerClassNames);
}
