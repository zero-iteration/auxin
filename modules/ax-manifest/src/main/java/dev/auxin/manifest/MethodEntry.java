package dev.auxin.manifest;

import java.util.Objects;

/**
 * One probed method, and the slot its probe occupies in the class's probe array.
 *
 * <p>WHY {@link #idx()} is the most dangerous field in this project: the agent writes probe hits by
 * array index, and the analysis reads them back by index. If the build and the agent ever disagree
 * about which slot belongs to which method, coverage is silently attributed to the wrong method and
 * nothing anywhere reports an error -- a live method is declared dead. The index is therefore never
 * derived from bytecode visit order; it comes from {@link ProbeIndex}'s pure lexicographic rule, and
 * {@link ClassEntry#schemaHash()} exists so the agent can refuse to probe a class whose member list
 * has drifted (A14 / CONTRACTS section 1).
 *
 * <p>Three of the flags exist purely to stop a confidently wrong "dead" verdict:
 * <ul>
 *   <li>{@link #dynamicallyObservable()} (C51): TOSEM 2022 showed constant-returning accessors,
 *       empty methods and single-instruction bodies may never appear as a distinct frame at
 *       runtime, so "unobserved" tells us nothing about them.</li>
 *   <li>{@link #shortCircuitable()}: a {@code @Cacheable} method with a warm cache never executes
 *       even when the feature is used constantly. The probe correctly reports "never invoked" and
 *       the conclusion "dead" would still be wrong.</li>
 *   <li>{@link #testOnlyReachable()} (C50): a library reachable only from its own test must not
 *       keep itself alive. Tri-state on purpose -- {@code null} means "we could not decide", and
 *       that must never be rendered as {@code false}.</li>
 * </ul>
 *
 * <p>Built through {@link #builder(int, String, String)}: the entry carries four adjacent booleans
 * whose meanings are not interchangeable, and a positional constructor for those is a defect
 * waiting to happen.
 */
public final class MethodEntry {

    public static final String ACCESS_PUBLIC = "public";
    public static final String ACCESS_PROTECTED = "protected";
    public static final String ACCESS_PRIVATE = "private";
    public static final String ACCESS_PACKAGE_PRIVATE = "package-private";

    /** {@link #line()} value used when the class carries no {@code LineNumberTable}. */
    public static final int UNKNOWN_LINE = -1;

    /** {@link #sccId()} value used when no call-graph component analysis was run. */
    public static final int UNKNOWN_SCC = -1;

    private final int idx;
    private final String name;
    private final String desc;
    private final int line;
    private final String access;
    private final boolean synthetic;
    private final boolean tier2;
    private final boolean dynamicallyObservable;
    private final boolean shortCircuitable;
    private final int sccId;
    private final Boolean testOnlyReachable;

    private MethodEntry(Builder b) {
        if (b.idx < 0) {
            throw new IllegalArgumentException("idx must be >= 0, got " + b.idx);
        }
        this.idx = b.idx;
        this.name = require(b.name, "name");
        this.desc = require(b.desc, "desc");
        this.line = b.line;
        this.access = require(b.access, "access");
        this.synthetic = b.synthetic;
        this.tier2 = b.tier2;
        this.dynamicallyObservable = b.dynamicallyObservable;
        this.shortCircuitable = b.shortCircuitable;
        this.sccId = b.sccId;
        this.testOnlyReachable = b.testOnlyReachable;
    }

    public static Builder builder(int idx, String name, String desc) {
        return new Builder(idx, name, desc);
    }

    /** Probe slot within the owning class. See {@link ProbeIndex} for the assignment rule. */
    public int idx() {
        return idx;
    }

    public String name() {
        return name;
    }

    /** JVM method descriptor, e.g. {@code (Ljava/util/List;)Lcom/acme/Rate;}. */
    public String desc() {
        return desc;
    }

    /** First source line of the method body, or {@link #UNKNOWN_LINE}. */
    public int line() {
        return line;
    }

    /** One of the {@code ACCESS_*} constants. */
    public String access() {
        return access;
    }

    /**
     * Always {@code false} for entries produced by ax-static, because synthetic methods are skipped
     * before indexing. The field is kept so a manifest written by some other producer round-trips.
     */
    public boolean synthetic() {
        return synthetic;
    }

    /** Whether this method is on the tier-2 latency allowlist. */
    public boolean tier2() {
        return tier2;
    }

    /** {@code false} when runtime absence of this method proves nothing (C51). */
    public boolean dynamicallyObservable() {
        return dynamicallyObservable;
    }

    /**
     * {@code true} when a framework proxy may return before the body runs -- caching, retry,
     * circuit breaker, transaction advice. Runtime absence proves nothing for these either.
     */
    public boolean shortCircuitable() {
        return shortCircuitable;
    }

    /** Strongly-connected component id in the call graph, or {@link #UNKNOWN_SCC}. */
    public int sccId() {
        return sccId;
    }

    /** Tri-state (C50). {@code null} = undecidable; never collapse it to {@code false}. */
    public Boolean testOnlyReachable() {
        return testOnlyReachable;
    }

    /**
     * Returns a copy carrying call-graph linkage results. Used by ax-static after the graph is
     * built, because the component id cannot be known while methods are still being indexed.
     */
    public MethodEntry withLinkage(int newSccId, Boolean newTestOnlyReachable) {
        return toBuilder().sccId(newSccId).testOnlyReachable(newTestOnlyReachable).build();
    }

    public Builder toBuilder() {
        return new Builder(idx, name, desc)
                .line(line)
                .access(access)
                .synthetic(synthetic)
                .tier2(tier2)
                .dynamicallyObservable(dynamicallyObservable)
                .shortCircuitable(shortCircuitable)
                .sccId(sccId)
                .testOnlyReachable(testOnlyReachable);
    }

    /** {@code name + desc}: the unit that {@link SchemaHash} hashes and that identity is keyed on. */
    public String nameAndDesc() {
        return name + desc;
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
        if (!(o instanceof MethodEntry)) {
            return false;
        }
        MethodEntry other = (MethodEntry) o;
        return idx == other.idx
                && line == other.line
                && synthetic == other.synthetic
                && tier2 == other.tier2
                && dynamicallyObservable == other.dynamicallyObservable
                && shortCircuitable == other.shortCircuitable
                && sccId == other.sccId
                && name.equals(other.name)
                && desc.equals(other.desc)
                && access.equals(other.access)
                && Objects.equals(testOnlyReachable, other.testOnlyReachable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idx, name, desc, line, access, synthetic, tier2,
                dynamicallyObservable, shortCircuitable, sccId, testOnlyReachable);
    }

    @Override
    public String toString() {
        return "#" + idx + ' ' + name + desc;
    }

    /**
     * Mutable builder for {@link MethodEntry}.
     *
     * <p>The defaults are chosen so that a caller who forgets a flag gets the <em>safe</em> value:
     * a method is assumed not dynamically observable and assumed short-circuitable until something
     * proves otherwise, because both of those only ever push a verdict towards UNKNOWN.
     */
    public static final class Builder {
        private final int idx;
        private final String name;
        private final String desc;
        private int line = UNKNOWN_LINE;
        private String access = ACCESS_PACKAGE_PRIVATE;
        private boolean synthetic;
        private boolean tier2;
        private boolean dynamicallyObservable;
        private boolean shortCircuitable = true;
        private int sccId = UNKNOWN_SCC;
        private Boolean testOnlyReachable;

        private Builder(int idx, String name, String desc) {
            this.idx = idx;
            this.name = name;
            this.desc = desc;
        }

        public Builder line(int value) {
            this.line = value;
            return this;
        }

        public Builder access(String value) {
            this.access = value;
            return this;
        }

        public Builder synthetic(boolean value) {
            this.synthetic = value;
            return this;
        }

        public Builder tier2(boolean value) {
            this.tier2 = value;
            return this;
        }

        public Builder dynamicallyObservable(boolean value) {
            this.dynamicallyObservable = value;
            return this;
        }

        public Builder shortCircuitable(boolean value) {
            this.shortCircuitable = value;
            return this;
        }

        public Builder sccId(int value) {
            this.sccId = value;
            return this;
        }

        public Builder testOnlyReachable(Boolean value) {
            this.testOnlyReachable = value;
            return this;
        }

        public MethodEntry build() {
            return new MethodEntry(this);
        }
    }
}
