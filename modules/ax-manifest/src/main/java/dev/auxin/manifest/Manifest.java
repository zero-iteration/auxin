package dev.auxin.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The build-time inventory of an artifact: every class, its probe layout, its entry points, and the
 * corroborating static call graph.
 *
 * <p>This is the frozen coupling point of CONTRACTS section 1. ax-static writes it, ax-agent and
 * gt-collector read it, and none of the three may know anything about the others' internals.
 *
 * <p>WHY the constructor normalises (sorts and de-duplicates) instead of storing what it was given:
 * the manifest is a build artifact that gets diffed between builds to answer "did the probe layout
 * move?". If the class list came out in scan order, every jar-entry reordering would show up as a
 * spurious diff and a real index shift would be lost in the noise. Sorting also means two builds of
 * identical source produce byte-identical manifests, which is the only way a reproducible-build
 * claim survives. De-duplication is for the call graph, where the same edge is legitimately emitted
 * from several call sites in one method body.
 */
public final class Manifest {

    /** The only schema version this module reads or writes. */
    /**
     * The manifest schema version this writer emits.
     *
     * <p>BUG #19: this was {@code 1} while the writer emitted every one of v2's six fields
     * ({@code isTest}, {@code dynamicallyObservable}, {@code shortCircuitable}, {@code sccId},
     * {@code testOnlyReachable}, and {@code semantics} on edges). CONTRACTS.md §1 says a v1
     * manifest's absent fields default to their safe direction, "which means a v1 manifest
     * nominates nothing" -- so a reader that honoured the contract would ignore those fields in a
     * document that carries them, and read a fully-populated manifest as nominating nothing.
     *
     * <p>It stayed invisible because the deleted in-agent {@code ManifestTool} stand-in stamped
     * {@code 2}, and the agent tolerates either. Same root cause as bug #17 on the wire: two
     * components each verified against a stand-in for the other, so the seam went untested.
     */
    public static final int SCHEMA_VERSION = 2;

    /**
     * Manifest versions this build can READ.
     *
     * <p>A set, not a single constant, for the reason bug #17 taught us on the wire: every
     * addition so far has been purely additive, and {@link ManifestReader} already ignores
     * unknown keys and defaults every absent field to its safe direction. A reader pinned to one
     * exact version rejects a document it could read perfectly well -- and worse, pins whichever
     * version it was written against, so bumping the writer breaks every older fixture at once.
     *
     * <p>The refusal itself still matters and is still here: an <em>unknown</em> version may carry
     * a different probe-index assignment (A14 defect 2), and silently accepting that is how
     * coverage gets attributed to the wrong methods.
     */
    public static final java.util.Set<Integer> READABLE_SCHEMA_VERSIONS =
            java.util.Collections.unmodifiableSet(
                    new java.util.LinkedHashSet<Integer>(java.util.Arrays.asList(1, 2)));

    private final int schemaVersion;
    private final String buildSha;
    private final String artifact;
    private final String generatedAt;
    private final List<ClassEntry> classes;
    private final List<EntryPoint> entryPoints;
    private final List<CallEdge> callEdges;

    public Manifest(int schemaVersion,
                    String buildSha,
                    String artifact,
                    String generatedAt,
                    List<ClassEntry> classes,
                    List<EntryPoint> entryPoints,
                    List<CallEdge> callEdges) {
        this.schemaVersion = schemaVersion;
        this.buildSha = require(buildSha, "buildSha");
        this.artifact = require(artifact, "artifact");
        this.generatedAt = require(generatedAt, "generatedAt");
        this.classes = normalise(classes, CLASS_ORDER);
        this.entryPoints = normalise(entryPoints, ENTRY_POINT_ORDER);
        this.callEdges = normalise(callEdges, CALL_EDGE_ORDER);
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** Half of the identity triple {@code (buildSha, className, methodDesc)}. */
    public String buildSha() {
        return buildSha;
    }

    public String artifact() {
        return artifact;
    }

    /** ISO-8601 instant, e.g. {@code 2026-09-12T10:00:00Z}. */
    public String generatedAt() {
        return generatedAt;
    }

    /** Sorted by class name. Immutable. */
    public List<ClassEntry> classes() {
        return classes;
    }

    /** Sorted and de-duplicated. Immutable. */
    public List<EntryPoint> entryPoints() {
        return entryPoints;
    }

    /**
     * Sorted and de-duplicated. Immutable.
     *
     * <p>Per PLAN-v2 this graph is a <b>corroborating signal only</b> -- A5 measured 61% of executed
     * methods missing from static graphs of Spring applications -- and it must never drive a
     * deletion cascade.
     */
    public List<CallEdge> callEdges() {
        return callEdges;
    }

    private static final Comparator<ClassEntry> CLASS_ORDER = new Comparator<ClassEntry>() {
        @Override
        public int compare(ClassEntry a, ClassEntry b) {
            return a.name().compareTo(b.name());
        }
    };

    private static final Comparator<EntryPoint> ENTRY_POINT_ORDER = new Comparator<EntryPoint>() {
        @Override
        public int compare(EntryPoint a, EntryPoint b) {
            int c = a.className().compareTo(b.className());
            if (c != 0) {
                return c;
            }
            c = a.method().compareTo(b.method());
            if (c != 0) {
                return c;
            }
            c = a.desc().compareTo(b.desc());
            return c != 0 ? c : a.kind().compareTo(b.kind());
        }
    };

    private static final Comparator<CallEdge> CALL_EDGE_ORDER = new Comparator<CallEdge>() {
        @Override
        public int compare(CallEdge a, CallEdge b) {
            int c = a.from().compareTo(b.from());
            if (c != 0) {
                return c;
            }
            c = a.to().compareTo(b.to());
            if (c != 0) {
                return c;
            }
            c = a.resolution().wireName().compareTo(b.resolution().wireName());
            return c != 0 ? c : a.semantics().wireName().compareTo(b.semantics().wireName());
        }
    };

    private static <T> List<T> normalise(List<T> input, Comparator<T> order) {
        List<T> copy = new ArrayList<T>(new LinkedHashSet<T>(Objects.requireNonNull(input)));
        Collections.sort(copy, order);
        return Collections.unmodifiableList(copy);
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
        if (!(o instanceof Manifest)) {
            return false;
        }
        Manifest other = (Manifest) o;
        return schemaVersion == other.schemaVersion
                && buildSha.equals(other.buildSha)
                && artifact.equals(other.artifact)
                && generatedAt.equals(other.generatedAt)
                && classes.equals(other.classes)
                && entryPoints.equals(other.entryPoints)
                && callEdges.equals(other.callEdges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, buildSha, artifact, generatedAt, classes, entryPoints, callEdges);
    }

    @Override
    public String toString() {
        return "Manifest{" + artifact + '@' + buildSha + ", classes=" + classes.size()
                + ", entryPoints=" + entryPoints.size() + ", callEdges=" + callEdges.size() + '}';
    }
}
