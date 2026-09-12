package dev.auxin.manifest;

/**
 * Whether a {@link CallEdge} actually stands in the way of deleting its callee (C52).
 *
 * <p>WHY existence alone is not enough: Meta's SCARF gets its yield from classifying edges rather
 * than treating the graph as binary reachable/not-reachable. An {@code if (x instanceof Bar)} test
 * references {@code Bar}, but the site constant-folds once {@code Bar} is gone, so that reference
 * must not block {@code Bar}'s removal. A graph that cannot say this over-blocks and destroys the
 * yield.
 *
 * <p>This is deliberately <b>orthogonal</b> to {@link Resolution}: {@code resolution} says how sure
 * we are <em>who</em> is called, {@code semantics} says whether the reference <em>matters</em>.
 * The catalogue starts small and conservative -- anything not recognised as a no-op is
 * {@link #BLOCKING}.
 */
public enum EdgeSemantics {

    /** The default. The reference constrains removal of the callee. */
    BLOCKING("blocking"),

    /**
     * The reference disappears with its target: {@code instanceof} tests, {@code .class} literals,
     * {@code getClass()} queries and catch-clause type references.
     */
    NOOP("noop");

    private final String wireName;

    EdgeSemantics(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static EdgeSemantics fromWireName(String wireName) {
        for (EdgeSemantics s : values()) {
            if (s.wireName.equals(wireName)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown edge semantics: " + wireName);
    }
}
