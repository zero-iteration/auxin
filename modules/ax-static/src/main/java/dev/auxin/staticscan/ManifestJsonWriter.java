package dev.auxin.staticscan;

import dev.auxin.manifest.CallEdge;
import dev.auxin.manifest.ClassEntry;
import dev.auxin.manifest.EntryPoint;
import dev.auxin.manifest.Manifest;
import dev.auxin.manifest.ManifestWriter;
import dev.auxin.manifest.MethodEntry;
import dev.auxin.manifest.json.JsonEmitter;
import dev.auxin.staticscan.tier2.Tier2Selection;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Writes {@code auxin-manifest.json} with the additive per-method {@code tier2Reason} field.
 *
 * <p>WHY this exists next to {@link ManifestWriter} instead of being that class: {@code tier2Reason}
 * is a reason string that only the producer knows -- it is not derivable from the typed contract
 * model, which carries {@code tier2} as a bare boolean -- and ax-manifest is frozen for this change.
 * {@code ManifestReader} documents that <em>unknown keys are ignored, because a newer producer
 * adding a field is additive and safe</em>, so emitting it does not move the schema version and
 * every existing reader keeps working.
 *
 * <p>WHY the duplication is safe rather than merely tolerated: the field order here is a copy of
 * {@link ManifestWriter}'s, and a copy drifts. {@code ManifestJsonWriterTest} therefore asserts that
 * this writer's output, with the {@code tier2Reason} lines deleted, is <b>byte-identical</b> to
 * {@link ManifestWriter}'s. Any change to the frozen writer -- a new field, a reordering, a
 * formatting change -- fails that test loudly instead of producing two manifests that disagree.
 *
 * <p>An absent {@code tier2Reason} means the method is not tier-2. The field is omitted rather than
 * emitted as {@code null} so that the common case costs nothing in a document that is read by a
 * human as a build diff.
 */
public final class ManifestJsonWriter {

    private ManifestJsonWriter() {
    }

    /** Writes {@code manifest} as UTF-8 JSON, annotating tier-2 methods with their reason. */
    public static void write(Manifest manifest, Tier2Selection tier2, OutputStream out)
            throws IOException {
        write(manifest, tier2, new BufferedWriter(new OutputStreamWriter(out, "UTF-8")));
    }

    /** Writes to an already-decoded writer. Flushes; does not close. */
    public static void write(Manifest manifest, Tier2Selection tier2, Writer writer)
            throws IOException {
        JsonEmitter json = new JsonEmitter(writer);
        json.beginObject();
        json.name("schemaVersion").value(manifest.schemaVersion());
        json.name("buildSha").value(manifest.buildSha());
        json.name("artifact").value(manifest.artifact());
        json.name("generatedAt").value(manifest.generatedAt());

        json.name("classes").beginArray();
        for (ClassEntry cls : manifest.classes()) {
            writeClass(json, cls, tier2);
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

    private static void writeClass(JsonEmitter json, ClassEntry cls, Tier2Selection tier2)
            throws IOException {
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
            String reason = tier2.reasonFor(cls.name(), m.name(), m.desc());
            if (reason != null) {
                json.name("tier2Reason").value(reason);
            }
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
