package dev.auxin.manifest;

import dev.auxin.manifest.json.JsonParser;
import dev.auxin.manifest.json.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code auxin-manifest.json} into the typed contract model.
 *
 * <p>WHY the mapping is written out by hand instead of being reflective: every field of this schema
 * has a correctness consequence, and an explicit mapper is the only place where "this field is
 * missing" can be turned into a named, loud failure. Reflective binding would silently default the
 * missing field, and the fields most likely to be missing -- {@code idx}, {@code schemaHash} --
 * are the two that cause misattribution when wrong (A14).
 *
 * <p>WHY it refuses an unknown {@code schemaVersion}: CONTRACTS section 2 requires a schema
 * mismatch to be rejected loudly and never to silently merge or silently report zero. A reader that
 * best-effort-parses a future schema is a reader that reports coverage it does not understand.
 *
 * <p>Unknown <em>keys</em>, by contrast, are ignored. That asymmetry is deliberate: a newer producer
 * adding a field is additive and safe, whereas a newer producer changing the meaning of the schema
 * announces itself through the version number.
 */
public final class ManifestReader {

    private ManifestReader() {
    }

    /** Reads a manifest from a UTF-8 JSON stream. Does not close the stream. */
    public static Manifest read(InputStream in) throws IOException {
        return parse(readUtf8(in));
    }

    /** Reads a manifest from an in-memory document. */
    public static Manifest parse(String json) {
        Object root;
        try {
            root = JsonParser.parse(json);
        } catch (JsonSyntaxException e) {
            throw new ManifestFormatException("manifest is not well-formed JSON: " + e.getMessage(), e);
        }
        Map<String, Object> obj = asObject(root, "<root>");

        int schemaVersion = (int) requireLong(obj, "schemaVersion", "<root>");
        if (!Manifest.READABLE_SCHEMA_VERSIONS.contains(Integer.valueOf(schemaVersion))) {
            throw new ManifestFormatException("unsupported schemaVersion " + schemaVersion
                    + "; this build of ax-manifest reads " + Manifest.READABLE_SCHEMA_VERSIONS
                    + " and writes " + Manifest.SCHEMA_VERSION);
        }

        List<ClassEntry> classes = new ArrayList<ClassEntry>();
        for (Object raw : optionalArray(obj, "classes", "<root>")) {
            classes.add(readClass(asObject(raw, "classes[]")));
        }

        List<EntryPoint> entryPoints = new ArrayList<EntryPoint>();
        for (Object raw : optionalArray(obj, "entryPoints", "<root>")) {
            Map<String, Object> ep = asObject(raw, "entryPoints[]");
            entryPoints.add(new EntryPoint(
                    requireString(ep, "class", "entryPoints[]"),
                    requireString(ep, "method", "entryPoints[]"),
                    requireString(ep, "desc", "entryPoints[]"),
                    requireString(ep, "kind", "entryPoints[]")));
        }

        List<CallEdge> callEdges = new ArrayList<CallEdge>();
        for (Object raw : optionalArray(obj, "callEdges", "<root>")) {
            Map<String, Object> edge = asObject(raw, "callEdges[]");
            String from = requireString(edge, "from", "callEdges[]");
            String to = requireString(edge, "to", "callEdges[]");
            callEdges.add(new CallEdge(from, to,
                    readResolution(requireString(edge, "resolution", "callEdges[]"), from, to),
                    readSemantics(edge, from, to)));
        }

        return new Manifest(schemaVersion,
                requireString(obj, "buildSha", "<root>"),
                requireString(obj, "artifact", "<root>"),
                requireString(obj, "generatedAt", "<root>"),
                classes, entryPoints, callEdges);
    }

    private static ClassEntry readClass(Map<String, Object> cls) {
        String name = requireString(cls, "name", "classes[]");
        List<MethodEntry> methods = new ArrayList<MethodEntry>();
        for (Object raw : optionalArray(cls, "methods", name)) {
            methods.add(readMethod(asObject(raw, name + ".methods[]"), name));
        }
        try {
            return new ClassEntry(name,
                    optionalString(cls, "sourceFile", ""),
                    (int) requireLong(cls, "probeCount", name),
                    requireString(cls, "schemaHash", name),
                    optionalBoolean(cls, "isPublicApi", true),
                    optionalBoolean(cls, "isTest", false),
                    methods);
        } catch (IllegalArgumentException e) {
            throw new ManifestFormatException("invalid class entry '" + name + "': " + e.getMessage(), e);
        }
    }

    private static MethodEntry readMethod(Map<String, Object> m, String owner) {
        String where = owner + ".methods[]";
        try {
            return MethodEntry.builder(
                            (int) requireLong(m, "idx", where),
                            requireString(m, "name", where),
                            requireString(m, "desc", where))
                    .line((int) optionalLong(m, "line", MethodEntry.UNKNOWN_LINE))
                    .access(optionalString(m, "access", MethodEntry.ACCESS_PACKAGE_PRIVATE))
                    .synthetic(optionalBoolean(m, "synthetic", false))
                    .tier2(optionalBoolean(m, "tier2", false))
                    // Absent means "the producer did not compute it". C51's safe direction is
                    // false: treat it as something whose absence at runtime proves nothing.
                    .dynamicallyObservable(optionalBoolean(m, "dynamicallyObservable", false))
                    // Same reasoning: absent means we cannot rule out a proxy short-circuit.
                    .shortCircuitable(optionalBoolean(m, "shortCircuitable", true))
                    .sccId((int) optionalLong(m, "sccId", MethodEntry.UNKNOWN_SCC))
                    .testOnlyReachable(optionalTriState(m, "testOnlyReachable"))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ManifestFormatException("invalid method entry in '" + owner + "': " + e.getMessage(), e);
        }
    }

    private static Resolution readResolution(String wireName, String from, String to) {
        try {
            return Resolution.fromWireName(wireName);
        } catch (IllegalArgumentException e) {
            throw new ManifestFormatException("callEdge " + from + " -> " + to
                    + " has unknown resolution '" + wireName + "'", e);
        }
    }

    private static EdgeSemantics readSemantics(Map<String, Object> edge, String from, String to) {
        // Absent semantics means the producer predates C52. "blocking" is the conservative
        // reading: it can only ever keep a method alive, never mark one deletable.
        Object raw = edge.get("semantics");
        if (raw == null) {
            return EdgeSemantics.BLOCKING;
        }
        try {
            return EdgeSemantics.fromWireName(asString(raw, "semantics", "callEdges[]"));
        } catch (IllegalArgumentException e) {
            throw new ManifestFormatException("callEdge " + from + " -> " + to
                    + " has unknown semantics '" + raw + "'", e);
        }
    }

    private static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String where) {
        if (!(value instanceof Map)) {
            throw new ManifestFormatException(where + " must be a JSON object, found "
                    + describe(value));
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> optionalArray(Map<String, Object> obj, String key, String where) {
        Object value = obj.get(key);
        if (value == null) {
            return new ArrayList<Object>();
        }
        if (!(value instanceof List)) {
            throw new ManifestFormatException(where + '.' + key + " must be a JSON array, found "
                    + describe(value));
        }
        return (List<Object>) value;
    }

    private static String requireString(Map<String, Object> obj, String key, String where) {
        Object value = obj.get(key);
        if (value == null) {
            throw new ManifestFormatException("missing required field " + where + '.' + key);
        }
        return asString(value, key, where);
    }

    private static String asString(Object value, String key, String where) {
        if (!(value instanceof String)) {
            throw new ManifestFormatException(where + '.' + key + " must be a string, found "
                    + describe(value));
        }
        return (String) value;
    }

    private static String optionalString(Map<String, Object> obj, String key, String fallback) {
        Object value = obj.get(key);
        return value == null ? fallback : asString(value, key, "");
    }

    private static long requireLong(Map<String, Object> obj, String key, String where) {
        Object value = obj.get(key);
        if (value == null) {
            throw new ManifestFormatException("missing required field " + where + '.' + key);
        }
        if (!(value instanceof Long)) {
            throw new ManifestFormatException(where + '.' + key + " must be an integer, found "
                    + describe(value));
        }
        return (Long) value;
    }

    private static long optionalLong(Map<String, Object> obj, String key, long fallback) {
        Object value = obj.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Long)) {
            throw new ManifestFormatException(key + " must be an integer, found " + describe(value));
        }
        return (Long) value;
    }

    private static boolean optionalBoolean(Map<String, Object> obj, String key, boolean fallback) {
        Object value = obj.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean)) {
            throw new ManifestFormatException(key + " must be a boolean, found " + describe(value));
        }
        return (Boolean) value;
    }

    private static Boolean optionalTriState(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean)) {
            throw new ManifestFormatException(key + " must be a boolean or null, found " + describe(value));
        }
        return (Boolean) value;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            return "an object";
        }
        if (value instanceof List) {
            return "an array";
        }
        return value.getClass().getSimpleName().toLowerCase() + " '" + value + "'";
    }
}
