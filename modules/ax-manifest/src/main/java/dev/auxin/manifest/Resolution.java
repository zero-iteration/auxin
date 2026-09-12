package dev.auxin.manifest;

/**
 * How confidently a {@link CallEdge}'s callee was determined.
 *
 * <p>WHY this is a first-class, mandatory field rather than an omission: validation A5 measured
 * that <b>61% of executed methods are missing</b> from a static call graph of a Spring application,
 * and CONTRACTS section 4 requires {@code static_reachable=None} to never be collapsed into
 * {@code False}. An edge we could not resolve therefore has to be <em>emitted and labelled</em>,
 * because dropping it would make the graph look more complete than it is and would let a
 * DEAD_CANDIDATE verdict rest on an absence we manufactured.
 */
public enum Resolution {

    /** Statically bound: the callee is the method named in the constant pool, with no dispatch. */
    EXACT("exact"),

    /**
     * Virtual/interface dispatch resolved by class-hierarchy analysis over the scanned artifact
     * only. The real callee is one of the emitted candidates -- if the hierarchy is complete.
     */
    CHA("cha"),

    /**
     * The callee could not be determined: dispatch on a type outside the scanned artifact, or an
     * {@code invokedynamic} whose bootstrap method we do not understand. Never treat as "no edge".
     */
    UNRESOLVED("unresolved");

    private final String wireName;

    Resolution(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase token used in {@code auxin-manifest.json}. */
    public String wireName() {
        return wireName;
    }

    public static Resolution fromWireName(String wireName) {
        for (Resolution r : values()) {
            if (r.wireName.equals(wireName)) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown resolution: " + wireName);
    }
}
