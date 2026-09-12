package dev.auxin.manifest.json;

/**
 * Thrown when the byte stream is not well-formed JSON.
 *
 * <p>WHY a dedicated exception: the manifest is the single coupling point between the build and
 * the agent, and a silently mis-parsed manifest causes coverage to be attributed to the wrong
 * method (A14). Every parse failure therefore has to surface as a hard, located error rather than
 * as a default value, so this type always carries the character offset at which parsing gave up.
 */
public final class JsonSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    public JsonSyntaxException(String message, int offset) {
        super(message + " (at offset " + offset + ")");
        this.offset = offset;
    }

    /** Character offset into the parsed document at which the failure was detected. */
    public int offset() {
        return offset;
    }
}
