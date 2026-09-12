package dev.auxin.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One class in the artifact inventory, with its probe layout.
 *
 * <p>WHY {@link #schemaHash()} is stored next to the methods rather than derived on demand: the
 * agent computes the hash from the bytes it is about to transform and compares it with this value.
 * A mismatch means the class on the classpath is not the class the probe indices were computed for,
 * and the agent must refuse to probe it rather than write hits into the wrong slots (CONTRACTS
 * section 1). Identity is {@code (buildSha, className, methodDesc)} -- deliberately <b>not</b> a
 * hash of the runtime class bytes, which change with every recompile.
 *
 * <p>WHY {@link #isPublicApi()} defaults towards {@code true}: downstream consumers of a library are
 * invisible to us, so a class we are unsure about has to stay UNKNOWN rather than become a deletion
 * candidate (PLAN-v2 correctness rule).
 *
 * <p>WHY {@link #isTest()} exists at all rather than test classes simply being dropped: C50. Test
 * classes must remain in the graph so that a library which only its own test reaches collapses into
 * the same component and neither keeps the other alive. Dropping them would hide that cycle.
 */
public final class ClassEntry {

    private final String name;
    private final String sourceFile;
    private final int probeCount;
    private final String schemaHash;
    private final boolean isPublicApi;
    private final boolean isTest;
    private final List<MethodEntry> methods;

    public ClassEntry(String name,
                      String sourceFile,
                      int probeCount,
                      String schemaHash,
                      boolean isPublicApi,
                      boolean isTest,
                      List<MethodEntry> methods) {
        this.name = require(name, "name");
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.schemaHash = require(schemaHash, "schemaHash");
        this.isPublicApi = isPublicApi;
        this.isTest = isTest;
        this.methods = Collections.unmodifiableList(
                new ArrayList<MethodEntry>(Objects.requireNonNull(methods, "methods")));
        if (probeCount != this.methods.size()) {
            // A probeCount that disagrees with the method list is exactly the corruption that
            // causes silent misattribution, so it is rejected at construction rather than carried.
            throw new IllegalArgumentException("probeCount " + probeCount + " != methods.size() "
                    + this.methods.size() + " for " + name);
        }
        this.probeCount = probeCount;
    }

    /**
     * Builds an entry, deriving {@code probeCount} and {@code schemaHash} from the method list so
     * the two can never drift apart.
     */
    public static ClassEntry of(String name,
                                String sourceFile,
                                boolean isPublicApi,
                                boolean isTest,
                                List<MethodEntry> methods) {
        List<String> nameAndDescs = new ArrayList<String>(methods.size());
        for (MethodEntry m : methods) {
            nameAndDescs.add(m.nameAndDesc());
        }
        return new ClassEntry(name, sourceFile, methods.size(),
                SchemaHash.ofNameAndDescs(nameAndDescs), isPublicApi, isTest, methods);
    }

    /**
     * Returns a copy with replacement method entries. The caller must not change any
     * {@code name}/{@code desc}; only post-graph annotations such as the component id.
     */
    public ClassEntry withMethods(List<MethodEntry> newMethods) {
        return new ClassEntry(name, sourceFile, newMethods.size(), schemaHash, isPublicApi, isTest, newMethods);
    }

    /** Binary class name in dotted form. */
    public String name() {
        return name;
    }

    /** Value of the {@code SourceFile} attribute, or {@code ""} if the class was compiled without it. */
    public String sourceFile() {
        return sourceFile;
    }

    public int probeCount() {
        return probeCount;
    }

    /** Lowercase hex SHA-256 over the sorted {@code name+desc} list. See {@link SchemaHash}. */
    public String schemaHash() {
        return schemaHash;
    }

    public boolean isPublicApi() {
        return isPublicApi;
    }

    /** {@code true} when this class is test code and therefore cannot testify to liveness (C50). */
    public boolean isTest() {
        return isTest;
    }

    /** Probed methods, ordered by {@link MethodEntry#idx()}. Immutable. */
    public List<MethodEntry> methods() {
        return methods;
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
        if (!(o instanceof ClassEntry)) {
            return false;
        }
        ClassEntry other = (ClassEntry) o;
        return probeCount == other.probeCount
                && isPublicApi == other.isPublicApi
                && isTest == other.isTest
                && name.equals(other.name)
                && sourceFile.equals(other.sourceFile)
                && schemaHash.equals(other.schemaHash)
                && methods.equals(other.methods);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, sourceFile, probeCount, schemaHash, isPublicApi, isTest, methods);
    }

    @Override
    public String toString() {
        return name + " [" + probeCount + " probes]";
    }
}
