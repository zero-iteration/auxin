package io.auxin.trace.runtime;

import java.util.List;

/**
 * One structural observation. <b>Never a value</b>, with four exceptions that are values by
 * design and PII-safe by inspection: a primitive number, a boolean, an enum constant's
 * {@code name()} and an exception's class name.
 *
 * <p>The kinds, and why this list is closed:
 * <ul>
 *   <li>{@code size} — {@code Collection.size()} / {@code Map.size()} / array length. The single
 *       highest-value signal: "126 fares in, 94 out" localises where data was dropped without
 *       recording one fare.</li>
 *   <li>{@code null} / {@code nonnull} — the other half of almost every real defect.</li>
 *   <li>{@code enum} — the constant's {@code name()}. A closed set a developer wrote; not user
 *       data.</li>
 *   <li>{@code num} / {@code bool} — primitives and their boxes.</li>
 *   <li>{@code len} — a {@code CharSequence}'s length. Deliberately NOT its content: a String is
 *       where free-text PII lives, and its length is the most that can be said safely.</li>
 *   <li>{@code class} — the runtime class name of anything else. Which implementation answered
 *       is often the whole finding, and a class name is not user data.</li>
 *   <li>{@code redacted} — the redaction pass refused this one, and says so rather than
 *       omitting it. A silently-missing observation is indistinguishable from a null.</li>
 *   <li>{@code error} — a projected getter threw. Its class name, nothing else.</li>
 * </ul>
 *
 * <p>There is no {@code value} kind, no {@code toString} kind, and no serialiser anywhere in
 * this module. That is the PII-safe-by-construction claim, and it is a claim about the code's
 * shape rather than about a filter's coverage.
 */
public final class Observation {

    public static final String SIZE = "size";
    public static final String NULL = "null";
    public static final String NONNULL = "nonnull";
    public static final String ENUM = "enum";
    public static final String NUM = "num";
    public static final String BOOL = "bool";
    public static final String LEN = "len";
    public static final String CLASS = "class";
    public static final String REDACTED = "redacted";
    public static final String ERROR = "error";

    /** The {@link SiteRegistry.ObsSite} id, or -1 for a projected child. */
    public final int siteId;
    public final String name;
    public final String kind;
    public final long num;
    public final double dbl;
    public final boolean hasDouble;
    public final String text;
    /** Whitelisted projections of this object, or null. */
    public final List<Observation> children;

    Observation(int siteId, String name, String kind, long num, String text,
                List<Observation> children) {
        this(siteId, name, kind, num, 0d, false, text, children);
    }

    Observation(int siteId, String name, String kind, long num, double dbl, boolean hasDouble,
                String text, List<Observation> children) {
        this.siteId = siteId;
        this.name = name;
        this.kind = kind;
        this.num = num;
        this.dbl = dbl;
        this.hasDouble = hasDouble;
        this.text = text;
        this.children = children;
    }

    /** Does this observation say the same thing as {@code other}? Used to collapse pass-throughs. */
    public boolean sameAs(Observation other) {
        if (other == null) return false;
        if (!kind.equals(other.kind)) return false;
        if (num != other.num) return false;
        if (hasDouble != other.hasDouble) return false;
        if (hasDouble && Double.compare(dbl, other.dbl) != 0) return false;
        if (text == null ? other.text != null : !text.equals(other.text)) return false;
        // Children are projections of a domain object; two frames whose projections differ are
        // not a pass-through even when their sizes agree.
        int a = children == null ? 0 : children.size();
        int b = other.children == null ? 0 : other.children.size();
        if (a != b) return false;
        for (int i = 0; i < a; i++) {
            if (!children.get(i).sameAs(other.children.get(i))) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return name + "=" + kind + (text != null ? ":" + text : ":" + num);
    }
}
