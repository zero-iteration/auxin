package dev.auxin.manifest;

import java.util.Objects;

/**
 * A method as observed by a bytecode scanner, before {@link ProbeIndex} decides whether it gets a
 * probe and which slot it occupies.
 *
 * <p>WHY this type is in ax-manifest and not in ax-static: the skip rule and the index rule are the
 * contract, and the contract is shared with ax-agent. ax-agent recomputes the same decision against
 * the bytes it is about to transform, so both sides must run <em>the same code</em>, not two
 * implementations of the same prose. That is also why this type is ASM-free -- ax-manifest must
 * never acquire a dependency that would have to be shaded into a host application.
 */
public final class MethodCandidate {

    /** Class initialisers never get a probe: PLAN-v2 says "never {@code <clinit>}", full stop. */
    public static final String CLASS_INITIALIZER = "<clinit>";

    private final String className;
    private final String methodName;
    private final String descriptor;
    private final int line;
    private final String access;
    private final boolean synthetic;
    private final boolean bridge;
    private final boolean isAbstract;
    private final boolean isNative;
    private final boolean tier2;
    private final boolean dynamicallyObservable;
    private final boolean shortCircuitable;

    private MethodCandidate(Builder b) {
        this.className = require(b.className, "className");
        this.methodName = require(b.methodName, "methodName");
        this.descriptor = require(b.descriptor, "descriptor");
        this.line = b.line;
        this.access = require(b.access, "access");
        this.synthetic = b.synthetic;
        this.bridge = b.bridge;
        this.isAbstract = b.isAbstract;
        this.isNative = b.isNative;
        this.tier2 = b.tier2;
        this.dynamicallyObservable = b.dynamicallyObservable;
        this.shortCircuitable = b.shortCircuitable;
    }

    public static Builder builder(String className, String methodName, String descriptor) {
        return new Builder(className, methodName, descriptor);
    }

    /**
     * The skip rule from CONTRACTS section 1: synthetic and bridge methods, {@code <clinit>}, and
     * abstract/native methods get no probe.
     *
     * <p>Not a filter for convenience -- each of these has no body we could instrument, or a body
     * the compiler generated, and probing them would consume an index slot that the agent's
     * independent recomputation would not allocate.
     */
    public boolean isProbeable() {
        return !synthetic
                && !bridge
                && !isAbstract
                && !isNative
                && !CLASS_INITIALIZER.equals(methodName);
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String descriptor() {
        return descriptor;
    }

    public int line() {
        return line;
    }

    public String access() {
        return access;
    }

    public boolean synthetic() {
        return synthetic;
    }

    public boolean bridge() {
        return bridge;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public boolean isNative() {
        return isNative;
    }

    public boolean tier2() {
        return tier2;
    }

    public boolean dynamicallyObservable() {
        return dynamicallyObservable;
    }

    public boolean shortCircuitable() {
        return shortCircuitable;
    }

    /** The sort key component used by {@link ProbeIndex}: {@code methodName + descriptor}. */
    public String nameAndDesc() {
        return methodName + descriptor;
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
        if (!(o instanceof MethodCandidate)) {
            return false;
        }
        MethodCandidate other = (MethodCandidate) o;
        return line == other.line
                && synthetic == other.synthetic
                && bridge == other.bridge
                && isAbstract == other.isAbstract
                && isNative == other.isNative
                && tier2 == other.tier2
                && dynamicallyObservable == other.dynamicallyObservable
                && shortCircuitable == other.shortCircuitable
                && className.equals(other.className)
                && methodName.equals(other.methodName)
                && descriptor.equals(other.descriptor)
                && access.equals(other.access);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, descriptor, line, access, synthetic, bridge,
                isAbstract, isNative, tier2, dynamicallyObservable, shortCircuitable);
    }

    @Override
    public String toString() {
        return className + '#' + methodName + descriptor;
    }

    /** Builder for {@link MethodCandidate}; defaults match {@link MethodEntry.Builder}. */
    public static final class Builder {
        private final String className;
        private final String methodName;
        private final String descriptor;
        private int line = MethodEntry.UNKNOWN_LINE;
        private String access = MethodEntry.ACCESS_PACKAGE_PRIVATE;
        private boolean synthetic;
        private boolean bridge;
        private boolean isAbstract;
        private boolean isNative;
        private boolean tier2;
        private boolean dynamicallyObservable;
        private boolean shortCircuitable = true;

        private Builder(String className, String methodName, String descriptor) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
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

        public Builder bridge(boolean value) {
            this.bridge = value;
            return this;
        }

        public Builder isAbstract(boolean value) {
            this.isAbstract = value;
            return this;
        }

        public Builder isNative(boolean value) {
            this.isNative = value;
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

        public MethodCandidate build() {
            return new MethodCandidate(this);
        }
    }
}
