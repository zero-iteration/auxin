package dev.auxin.manifest;

import java.util.Objects;

/**
 * A method the outside world can invoke without any in-artifact caller.
 *
 * <p>WHY entry points are recorded as raw evidence and not as a verdict: an HTTP handler has no
 * static caller anywhere in the jar, so a reachability analysis that starts nowhere would declare
 * every controller method dead. ax-static therefore records <em>why</em> it believes a method is an
 * entry point ({@link #kind()}) and leaves the framework semantics to whoever consumes the
 * manifest. Detection is by annotation <b>descriptor</b> only -- we deliberately do not attempt to
 * model Spring's conditional bean resolution, because getting that subtly wrong is worse than not
 * doing it.
 */
public final class EntryPoint {

    private final String className;
    private final String method;
    private final String desc;
    private final String kind;

    public EntryPoint(String className, String method, String desc, String kind) {
        this.className = require(className, "className");
        this.method = require(method, "method");
        this.desc = require(desc, "desc");
        this.kind = require(kind, "kind");
    }

    /** Binary class name in dotted form, e.g. {@code com.acme.shipping.RateController}. */
    public String className() {
        return className;
    }

    public String method() {
        return method;
    }

    /** JVM method descriptor, e.g. {@code (Ljava/lang/String;)V}. */
    public String desc() {
        return desc;
    }

    /** The evidence: the simple name of the annotation that matched, or {@code main}/{@code ServiceLoader}. */
    public String kind() {
        return kind;
    }

    private static String require(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or empty");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EntryPoint)) {
            return false;
        }
        EntryPoint other = (EntryPoint) o;
        return className.equals(other.className)
                && method.equals(other.method)
                && desc.equals(other.desc)
                && kind.equals(other.kind);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, method, desc, kind);
    }

    @Override
    public String toString() {
        return kind + ' ' + className + '#' + method + desc;
    }
}
