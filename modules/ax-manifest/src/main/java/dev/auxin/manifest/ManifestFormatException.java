package dev.auxin.manifest;

/**
 * Thrown when a document is syntactically valid JSON but is not a manifest we may act on.
 *
 * <p>WHY this is separate from {@code JsonSyntaxException}: the two failures need different
 * operator responses. A syntax error means the file is truncated or corrupt; a format error means
 * the producer and the consumer disagree about the schema -- the case CONTRACTS requires to fail
 * <b>loudly</b> rather than degrade into a partial read. There is no lenient mode, because a
 * manifest that is half-understood produces coverage attributed to the wrong methods.
 */
public final class ManifestFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ManifestFormatException(String message) {
        super(message);
    }

    public ManifestFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
