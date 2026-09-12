package dev.auxin.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round trip, determinism, and the loud-failure behaviour CONTRACTS requires of the reader. */
class ManifestJsonTest {

    private static Manifest sample() {
        MethodEntry pick = MethodEntry.builder(0, "pick", "(Ljava/util/List;)Lcom/acme/Rate;")
                .line(47)
                .access(MethodEntry.ACCESS_PUBLIC)
                .tier2(true)
                .dynamicallyObservable(true)
                .shortCircuitable(false)
                .sccId(3)
                .testOnlyReachable(Boolean.FALSE)
                .build();
        MethodEntry trivial = MethodEntry.builder(1, "size", "()I")
                .line(12)
                .access(MethodEntry.ACCESS_PUBLIC)
                .dynamicallyObservable(false)
                .shortCircuitable(true)
                .sccId(4)
                .testOnlyReachable(null)
                .build();
        ClassEntry selector = ClassEntry.of("com.acme.shipping.RateSelector", "RateSelector.java",
                false, false, List.of(pick, trivial));
        ClassEntry test = ClassEntry.of("com.acme.shipping.RateSelectorTest", "RateSelectorTest.java",
                true, true, List.of(MethodEntry.builder(0, "picksCheapest", "()V")
                        .access(MethodEntry.ACCESS_PUBLIC).dynamicallyObservable(true).build()));

        return new Manifest(1, "abc123def", "checkout-service", "2026-09-12T10:00:00Z",
                List.of(test, selector),
                List.of(new EntryPoint("com.acme.shipping.RateController", "quote",
                        "(Ljava/lang/String;)Lcom/acme/Rate;", "GetMapping")),
                List.of(new CallEdge("com.acme.shipping.RateController#quote(Ljava/lang/String;)Lcom/acme/Rate;",
                                "com.acme.shipping.RateSelector#pick(Ljava/util/List;)Lcom/acme/Rate;",
                                Resolution.CHA, EdgeSemantics.BLOCKING),
                        new CallEdge("com.acme.shipping.RateController#quote(Ljava/lang/String;)Lcom/acme/Rate;",
                                CallEdge.typeRef("com.acme.shipping.LegacyRate"),
                                Resolution.EXACT, EdgeSemantics.NOOP)));
    }

    private static byte[] write(Manifest manifest) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ManifestWriter.write(manifest, out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("write then read reproduces the manifest exactly")
    void roundTrips() throws IOException {
        Manifest original = sample();
        Manifest reparsed = ManifestReader.read(new ByteArrayInputStream(write(original)));
        assertEquals(original, reparsed);
    }

    @Test
    @DisplayName("the tri-state testOnlyReachable survives as null, never as false")
    void preservesTriState() throws IOException {
        String json = new String(write(sample()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"testOnlyReachable\": null"), json);

        Manifest reparsed = ManifestReader.parse(json);
        ClassEntry selector = reparsed.classes().get(0);
        assertEquals("com.acme.shipping.RateSelector", selector.name());
        assertEquals(Boolean.FALSE, selector.methods().get(0).testOnlyReachable());
        assertNull(selector.methods().get(1).testOnlyReachable());
    }

    @Test
    @DisplayName("serialisation is deterministic: two writes of equal manifests are byte-identical")
    void isDeterministic() throws IOException {
        assertArrayEquals(write(sample()), write(sample()));
    }

    @Test
    @DisplayName("classes are emitted in sorted order regardless of input order")
    void normalisesOrdering() throws IOException {
        String json = new String(write(sample()), StandardCharsets.UTF_8);
        int selector = json.indexOf("com.acme.shipping.RateSelector\"");
        int selectorTest = json.indexOf("com.acme.shipping.RateSelectorTest\"");
        assertTrue(selector > 0 && selectorTest > selector,
                "RateSelector must sort before RateSelectorTest; got " + selector + "/" + selectorTest);
    }

    @Test
    @DisplayName("duplicate call edges collapse; the graph is a set, not a multiset")
    void deduplicatesCallEdges() {
        CallEdge edge = new CallEdge("A#a()V", "B#b()V", Resolution.CHA, EdgeSemantics.BLOCKING);
        Manifest manifest = new Manifest(1, "sha", "art", "2026-09-12T10:00:00Z",
                List.of(), List.of(), List.of(edge, edge, edge));
        assertEquals(1, manifest.callEdges().size());
    }

    @Test
    @DisplayName("an edge that is both blocking and no-op is kept twice: the labels are not merged")
    void keepsDistinctSemanticsForTheSamePair() {
        Manifest manifest = new Manifest(1, "sha", "art", "2026-09-12T10:00:00Z", List.of(), List.of(),
                List.of(new CallEdge("A#a()V", "B#b()V", Resolution.CHA, EdgeSemantics.BLOCKING),
                        new CallEdge("A#a()V", "B#b()V", Resolution.CHA, EdgeSemantics.NOOP)));
        assertEquals(2, manifest.callEdges().size());
    }

    @Test
    @DisplayName("strings with quotes, backslashes, newlines and control characters round trip")
    void escapesCorrectly() throws IOException {
        String nasty = "a\"b\\c\nd\tefég";
        Manifest manifest = new Manifest(1, nasty, "artifact name", "2026-09-12T10:00:00Z",
                List.of(), List.of(), List.of());
        byte[] bytes = write(manifest);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\\u0001"), json);
        assertTrue(json.contains("\\n"), json);
        assertEquals(manifest, ManifestReader.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    @DisplayName("an unknown schemaVersion is rejected loudly, not best-effort parsed")
    void rejectsUnknownSchemaVersion() {
        // BUG #19: this used version 2 -- the version the writer actually emits -- so it pinned
        // "reject the manifest we ourselves produce" as correct. 99 is used now because the point
        // of the refusal is that an UNKNOWN version may carry a different probe-index assignment
        // (A14 defect 2), not that the number is large.
        ManifestFormatException e = assertThrows(ManifestFormatException.class,
                () -> ManifestReader.parse("{\"schemaVersion\":99,\"buildSha\":\"a\",\"artifact\":\"b\","
                        + "\"generatedAt\":\"t\",\"classes\":[],\"entryPoints\":[],\"callEdges\":[]}"));
        assertTrue(e.getMessage().contains("unsupported schemaVersion 99"), e.getMessage());
    }

    @Test
    @DisplayName("every version this build writes is a version it can read")
    void writesOnlyWhatItCanRead() {
        // BUG #19 regression. SCHEMA_VERSION was 1 while the writer emitted all six of v2's
        // fields, so a reader honouring CONTRACTS §1's v1 rule ("a v1 manifest nominates nothing")
        // would ignore fields the document actually carried. Invisible because the deleted
        // in-agent ManifestTool stand-in stamped 2 and the agent tolerated either -- the same
        // stand-in-for-each-other seam that hid bug #17 on the wire.
        assertTrue(Manifest.READABLE_SCHEMA_VERSIONS.contains(Manifest.SCHEMA_VERSION),
                "the writer emits " + Manifest.SCHEMA_VERSION + " but the reader accepts only "
                        + Manifest.READABLE_SCHEMA_VERSIONS);
        for (Integer v : Manifest.READABLE_SCHEMA_VERSIONS) {
            String json = "{\"schemaVersion\":" + v + ",\"buildSha\":\"a\",\"artifact\":\"b\","
                    + "\"generatedAt\":\"t\",\"classes\":[],\"entryPoints\":[],\"callEdges\":[]}";
            assertNotNull(ManifestReader.parse(json), "schemaVersion " + v + " must be readable");
        }
    }

    @Test
    @DisplayName("a missing required field names the field")
    void rejectsMissingRequiredField() {
        ManifestFormatException e = assertThrows(ManifestFormatException.class,
                () -> ManifestReader.parse("{\"schemaVersion\":1,\"artifact\":\"b\",\"generatedAt\":\"t\"}"));
        assertTrue(e.getMessage().contains("buildSha"), e.getMessage());
    }

    @Test
    @DisplayName("a probeCount that disagrees with the method list is rejected")
    void rejectsInconsistentProbeCount() {
        String json = "{\"schemaVersion\":1,\"buildSha\":\"a\",\"artifact\":\"b\",\"generatedAt\":\"t\","
                + "\"classes\":[{\"name\":\"C\",\"sourceFile\":\"C.java\",\"probeCount\":7,"
                + "\"schemaHash\":\"ff\",\"isPublicApi\":true,\"isTest\":false,"
                + "\"methods\":[{\"idx\":0,\"name\":\"f\",\"desc\":\"()V\"}]}]}";
        ManifestFormatException e =
                assertThrows(ManifestFormatException.class, () -> ManifestReader.parse(json));
        assertTrue(e.getMessage().contains("probeCount"), e.getMessage());
    }

    @Test
    @DisplayName("malformed JSON fails with an offset, not with a default value")
    void rejectsMalformedJson() {
        ManifestFormatException e = assertThrows(ManifestFormatException.class,
                () -> ManifestReader.parse("{\"schemaVersion\":1,}"));
        assertTrue(e.getMessage().contains("offset"), e.getMessage());
    }

    @Test
    @DisplayName("unknown keys from a newer producer are ignored, but new-field defaults stay safe")
    void ignoresUnknownKeysAndDefaultsSafely() {
        String json = "{\"schemaVersion\":1,\"buildSha\":\"a\",\"artifact\":\"b\",\"generatedAt\":\"t\","
                + "\"somethingNew\":{\"x\":1},"
                + "\"classes\":[{\"name\":\"C\",\"probeCount\":1,\"schemaHash\":\"ff\","
                + "\"methods\":[{\"idx\":0,\"name\":\"f\",\"desc\":\"()V\",\"whatIsThis\":true}]}],"
                + "\"callEdges\":[{\"from\":\"A#a()V\",\"to\":\"B#b()V\",\"resolution\":\"cha\"}]}";

        Manifest manifest = ManifestReader.parse(json);
        MethodEntry m = manifest.classes().get(0).methods().get(0);

        assertTrue(manifest.classes().get(0).isPublicApi(), "absent isPublicApi must default to the safe TRUE");
        assertFalse(manifest.classes().get(0).isTest());
        assertFalse(m.dynamicallyObservable(), "absent dynamicallyObservable must default to the safe FALSE");
        assertTrue(m.shortCircuitable(), "absent shortCircuitable must default to the safe TRUE");
        assertEquals(MethodEntry.UNKNOWN_SCC, m.sccId());
        assertNull(m.testOnlyReachable());
        assertEquals(EdgeSemantics.BLOCKING, manifest.callEdges().get(0).semantics(),
                "absent semantics must default to the conservative BLOCKING");
    }

    @Test
    @DisplayName("an unknown resolution token is rejected rather than silently downgraded")
    void rejectsUnknownResolution() {
        String json = "{\"schemaVersion\":1,\"buildSha\":\"a\",\"artifact\":\"b\",\"generatedAt\":\"t\","
                + "\"callEdges\":[{\"from\":\"A#a()V\",\"to\":\"B#b()V\",\"resolution\":\"maybe\"}]}";
        ManifestFormatException e =
                assertThrows(ManifestFormatException.class, () -> ManifestReader.parse(json));
        assertTrue(e.getMessage().contains("maybe"), e.getMessage());
    }
}
