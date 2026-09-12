package dev.auxin.staticscan.cli;

/**
 * Thrown when the command line is wrong.
 *
 * <p>Separate from a scan failure because the two deserve different exit codes: a CI pipeline should
 * be able to tell "you invoked me incorrectly" from "the artifact could not be scanned" without
 * grepping stderr.
 */
public final class UsageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
        super(message);
    }
}
