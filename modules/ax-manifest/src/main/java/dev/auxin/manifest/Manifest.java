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
    public static final int SCHEMA_VERSION = 1;

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
