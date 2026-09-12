package io.auxin.agent.manifest;

import io.auxin.agent.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The build-time artifact manifest (CONTRACTS section 1), read-only at runtime.
 *
 * <p>Hand-rolled because ax-manifest does not exist yet in modules/ — see the report. When
 * ax-manifest lands, {@link Manifest}, {@link ClassEntry}, {@link MethodEntry}, {@link SchemaHash}
 * and {@link Eligibility} move there verbatim and this module depends on it by relative path.
 *
 * <p>Probe indices are LOOKED UP here, never derived from ASM visit order (VALIDATION A14
 * defect 2 / C7: an upstream agent adding a synthetic method shifts every subsequent index and
 * the union then silently attributes coverage to the wrong methods).
 */
public final class Manifest {

    public static final class MethodEntry {
        public final int idx;
        public final String name;
        public final String desc;
        public final boolean tier2;
        /** C51: null = flag absent, FALSE = cannot be covered dynamically, do not probe. */
        public final Boolean dynamicallyObservable;

        MethodEntry(int idx, String name, String desc, boolean tier2, Boolean dynObservable) {
            this.idx = idx;
            this.name = name;
            this.desc = desc;
            this.tier2 = tier2;
            this.dynamicallyObservable = dynObservable;
        }

        public boolean notDynamicallyObservable() {
            return dynamicallyObservable != null && !dynamicallyObservable.booleanValue();
        }
    }

    public static final class ClassEntry {
        public final String name;          // dotted
        public final String internalName;  // slashed
        public final int probeCount;
        public final String schemaHash;
        public final boolean publicApi;
        /** True when any method is flagged tier2 — decides the ClassReader flags. */
        public final boolean hasTier2;
        private final Map<String, MethodEntry> byNameDesc;

        ClassEntry(String name, int probeCount, String schemaHash, boolean publicApi,
                   Map<String, MethodEntry> byNameDesc) {
            this.name = name;
            this.internalName = name.replace('.', '/');
            this.probeCount = probeCount;
            this.schemaHash = schemaHash;
            this.publicApi = publicApi;
            this.byNameDesc = byNameDesc;
            boolean t2 = false;
            for (MethodEntry me : byNameDesc.values()) {
                if (me.tier2) { t2 = true; break; }
            }
            this.hasTier2 = t2;
        }

        public MethodEntry method(String name, String desc) {
            return byNameDesc.get(name + desc);
        }

        public java.util.Collection<MethodEntry> methods() {
            return byNameDesc.values();
        }
    }

    private final int schemaVersion;
    private final String buildSha;
    private final String artifact;
    private final Map<String, ClassEntry> byInternalName;

    private Manifest(int schemaVersion, String buildSha, String artifact,
                     Map<String, ClassEntry> byInternalName) {
        this.schemaVersion = schemaVersion;
        this.buildSha = buildSha;
        this.artifact = artifact;
        this.byInternalName = byInternalName;
    }

    public int schemaVersion() { return schemaVersion; }

    /**
     * True when NO method in the manifest carries the C51 {@code dynamicallyObservable} flag.
     * CONTRACTS section 1 says an absent flag is assumed false (the safe direction), which means
     * the collector will treat every probe in this build as not-dynamically-observable and yield
     * zero dead candidates. That is safe, and silent, so the agent says it out loud at startup.
     */
    public boolean lacksObservabilityFlags() {
        for (ClassEntry c : byInternalName.values()) {
            for (MethodEntry m : c.methods()) {
                if (m.dynamicallyObservable != null) return false;
            }
        }
        return true;
    }
    public String buildSha() { return buildSha; }
    public String artifact() { return artifact; }
    public int classCount() { return byInternalName.size(); }

    /** @param internalName slashed, exactly as handed to a ClassFileTransformer. */
    public ClassEntry byInternalName(String internalName) {
        return byInternalName.get(internalName);
    }

    // ---------------- loading ----------------

    /**
     * Resolution order: explicit path, then {@code ./auxin-manifest.json}, then the
     * classpath resource {@code auxin-manifest.json}. Returns null (never throws) when
     * nothing is found — without a manifest the agent instruments nothing, which is the
     * fail-open posture.
     */
    public static Manifest load(String explicitPath) {
        try {
            if (explicitPath != null && explicitPath.length() > 0) {
                File f = new File(explicitPath);
                if (!f.isFile()) return null;
                return parse(readAll(new FileInputStream(f)));
            }
            File local = new File("auxin-manifest.json");
            if (local.isFile()) return parse(readAll(new FileInputStream(local)));
            InputStream in = Manifest.class.getClassLoader()
                    .getResourceAsStream("auxin-manifest.json");
            if (in != null) return parse(readAll(in));
            return null;
        } catch (Throwable t) {
            io.auxin.agent.util.Log.warn("manifest load failed: " + t, t);
            return null;
        }
    }

    public static Manifest parse(String json) {
        Map<String, Object> root = Json.asObject(Json.parse(json));
        if (root == null) throw new IllegalArgumentException("manifest root is not an object");

        int schemaVersion = Json.num(root, "schemaVersion", -1);
        String buildSha = Json.str(root, "buildSha", "");
        String artifact = Json.str(root, "artifact", "");

        Map<String, ClassEntry> classes = new HashMap<String, ClassEntry>();
        List<Object> arr = Json.asArray(root.get("classes"));
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                Map<String, Object> c = Json.asObject(arr.get(i));
                if (c == null) continue;
                String name = Json.str(c, "name", null);
                if (name == null) continue;

                Map<String, MethodEntry> methods = new HashMap<String, MethodEntry>();
                List<Object> ms = Json.asArray(c.get("methods"));
                if (ms != null) {
                    for (int j = 0; j < ms.size(); j++) {
                        Map<String, Object> m = Json.asObject(ms.get(j));
                        if (m == null) continue;
                        String mn = Json.str(m, "name", null);
                        String md = Json.str(m, "desc", null);
                        if (mn == null || md == null) continue;
                        Boolean t2 = Json.bool(m, "tier2");
                        methods.put(mn + md, new MethodEntry(
                                Json.num(m, "idx", -1), mn, md,
                                t2 != null && t2.booleanValue(),
                                Json.bool(m, "dynamicallyObservable")));
                    }
                }
                Boolean api = Json.bool(c, "isPublicApi");
                ClassEntry e = new ClassEntry(name,
                        Json.num(c, "probeCount", methods.size()),
                        Json.str(c, "schemaHash", ""),
                        api != null && api.booleanValue(),
                        Collections.unmodifiableMap(methods));
                classes.put(e.internalName, e);
            }
        }
        return new Manifest(schemaVersion, buildSha, artifact, Collections.unmodifiableMap(classes));
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
