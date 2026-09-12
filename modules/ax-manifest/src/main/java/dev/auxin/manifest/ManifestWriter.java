package dev.auxin.manifest;

import dev.auxin.manifest.json.JsonEmitter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Serialises a {@link Manifest} to the frozen {@code auxin-manifest.json} form.
 *
 * <p>WHY the field order is hard-coded rather than driven by a map: the document is a build
 * artifact whose diff between two builds is a review surface. A stable key order means the only
 * lines that move are the ones that actually changed. Combined with {@link Manifest}'s sorting,
 * two builds of identical source produce byte-identical output.
 *
 * <p>The writer deliberately does not close the supplied stream -- callers frequently write a
 * manifest into an archive entry or an already-managed channel -- but it does flush, because a
 * half-written manifest that looks complete is worse than no manifest.
 */
public final class ManifestWriter {

    private ManifestWriter() {
    }

    /** Writes {@code manifest} as UTF-8 JSON. Flushes; does not close. */
    public static void write(Manifest manifest, OutputStream out) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
        write(manifest, writer);
    }

    /** Writes {@code manifest} to an already-decoded writer. Flushes; does not close. */
    public static void write(Manifest manifest, Writer writer) throws IOException {
        JsonEmitter json = new JsonEmitter(writer);
        json.beginObject();
        json.name("schemaVersion").value(manifest.schemaVersion());
        json.name("buildSha").value(manifest.buildSha());
        json.name("artifact").value(manifest.artifact());
        json.name("generatedAt").value(manifest.generatedAt());

        json.name("classes").beginArray();
        for (ClassEntry cls : manifest.classes()) {
            writeClass(json, cls);
        }
        json.endArray();

        json.name("entryPoints").beginArray();
        for (EntryPoint ep : manifest.entryPoints()) {
            json.beginObject();
            json.name("class").value(ep.className());
            json.name("method").value(ep.method());
            json.name("desc").value(ep.desc());
            json.name("kind").value(ep.kind());
            json.endObject();
        }
        json.endArray();

        json.name("callEdges").beginArray();
        for (CallEdge edge : manifest.callEdges()) {
            json.beginObject();
            json.name("from").value(edge.from());
            json.name("to").value(edge.to());
            json.name("resolution").value(edge.resolution().wireName());
            json.name("semantics").value(edge.semantics().wireName());
            json.endObject();
        }
        json.endArray();

        json.endObject();
        json.finish();
    }

    private static void writeClass(JsonEmitter json, ClassEntry cls) throws IOException {
        json.beginObject();
        json.name("name").value(cls.name());
        json.name("sourceFile").value(cls.sourceFile());
        json.name("probeCount").value(cls.probeCount());
        json.name("schemaHash").value(cls.schemaHash());
        json.name("isPublicApi").value(cls.isPublicApi());
        json.name("isTest").value(cls.isTest());
        json.name("methods").beginArray();
        for (MethodEntry m : cls.methods()) {
            json.beginObject();
            json.name("idx").value(m.idx());
            json.name("name").value(m.name());
            json.name("desc").value(m.desc());
            json.name("line").value(m.line());
            json.name("access").value(m.access());
            json.name("synthetic").value(m.synthetic());
            json.name("tier2").value(m.tier2());
            json.name("dynamicallyObservable").value(m.dynamicallyObservable());
            json.name("shortCircuitable").value(m.shortCircuitable());
            json.name("sccId").value(m.sccId());
            // Tri-state: null must survive as JSON null. Rendering it false would assert that the
            // method is production-reachable, which is exactly the claim C50 forbids us to invent.
            json.name("testOnlyReachable").value(m.testOnlyReachable());
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }
}
