package ax.bench.gen;

/** The probe patterns under test in G1. */
public enum ProbeStyle {
    /** No probe at all. The control arm. */
    NONE,
    /** (a) LDC condy; CHECKCAST [Z; push idx; ICONST_1; BASTORE. 5 insns, no branch, no frame. */
    BLIND,
    /** (b) if (!p[idx]) p[idx] = true; condy loaded twice. One StackMapTable entry PER PROBE SITE. */
    READ_STORE,
    /** (c) as (b) but the array is hoisted into a local once per method. One frame per method + per probe. */
    READ_STORE_HOISTED,
    /** bonus: blind store with the array hoisted to a local. This is what JaCoCo actually emits. */
    BLIND_HOISTED;

    public boolean hoists() {
        return this == READ_STORE_HOISTED || this == BLIND_HOISTED;
    }

    public boolean branches() {
        return this == READ_STORE || this == READ_STORE_HOISTED;
    }
}
