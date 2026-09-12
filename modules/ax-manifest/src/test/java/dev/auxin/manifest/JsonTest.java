package dev.auxin.manifest;

import dev.auxin.manifest.json.JsonEmitter;
import dev.auxin.manifest.json.JsonParser;
import dev.auxin.manifest.json.JsonSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-written JSON layer exists so ax-agent never has to put Jackson on a host classpath.
 * These tests pin the behaviour that the strictness claim rests on.
 */
class JsonTest {

    @Test
    @DisplayName("integral numbers stay integral so large ids do not drift through double")
    void keepsIntegersIntegral() {
        Map<?, ?> obj = (Map<?, ?>) JsonParser.parse("{\"a\":9007199254740993,\"b\":1.5}");
        assertEquals(Long.valueOf(9007199254740993L), obj.get("a"));
        assertEquals(Double.valueOf(1.5), obj.get("b"));
    }

    @Test
    @DisplayName("null, true, false, nested arrays and objects all parse")
    void parsesTheWholeGrammar() {
        Map<?, ?> obj = (Map<?, ?>) JsonParser.parse(
                "{\"n\":null,\"t\":true,\"f\":false,\"a\":[1,[2],{\"k\":\"v\"}],\"e\":[],\"o\":{}}");
        assertNull(obj.get("n"));
        assertEquals(Boolean.TRUE, obj.get("t"));
        assertEquals(Boolean.FALSE, obj.get("f"));
        assertEquals(3, ((List<?>) obj.get("a")).size());
        assertTrue(((List<?>) obj.get("e")).isEmpty());
        assertTrue(((Map<?, ?>) obj.get("o")).isEmpty());
    }

    @Test
    @DisplayName("escapes, including surrogate pairs, decode correctly")
    void decodesEscapes() {
        assertEquals("\"\\/\b\f\n\r\té😀",
                JsonParser.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\ud83d\\ude00\""));
    }

    @Test
    @DisplayName("trailing commas, comments, single quotes and trailing content are all rejected")
    void isStrict() {
        assertThrows(JsonSyntaxException.class, () -> JsonParser.parse("[1,]"));
        assertThrows(JsonSyntaxException.class, () -> JsonParser.parse("{\"a\":1,}"));
        assertThrows(JsonSyntaxException.class, () -> JsonParser.parse("{} // hi"));
        assertThrows(JsonSyntaxException.class, () -> JsonParser.parse("{'a':1}"));
        assertThrows(JsonSyntaxException.class, () -> JsonParser.parse("{} {}"));
        assertThrows(JsonSyntaxException.class, () -> JsonParser.parse("{\"a\":1,\"a\":2}"));
        assertThrows(JsonSyntaxException.class, () -> JsonParser.parse("\"unterminated"));
    }

    @Test
    @DisplayName("the emitter refuses to produce a structurally invalid document")
    void emitterEnforcesStructure() throws IOException {
        JsonEmitter json = new JsonEmitter(new StringWriter());
        json.beginObject();
        assertThrows(IllegalStateException.class, () -> json.value("no name yet"));
        json.name("a");
        assertThrows(IllegalStateException.class, () -> json.name("b"));
        json.value(1L);
        assertThrows(IllegalStateException.class, json::endArray);
    }

    @Test
    @DisplayName("an unbalanced document fails at finish() rather than being written out truncated")
    void emitterRejectsUnbalancedDocument() throws IOException {
        JsonEmitter json = new JsonEmitter(new StringWriter());
        json.beginObject();
        assertThrows(IllegalStateException.class, json::finish);
    }

    @Test
    @DisplayName("indentation is exact, so a manifest diff shows only what changed")
    void indentsPredictably() throws IOException {
        StringWriter out = new StringWriter();
        JsonEmitter json = new JsonEmitter(out);
        json.beginObject();
        json.name("items").beginArray();
        json.beginObject().name("k").value(1L).endObject();
        json.endArray();
        json.endObject();
        json.finish();

        assertEquals(String.join("\n",
                "{",
                "  \"items\": [",
                "    {",
                "      \"k\": 1",
                "    }",
                "  ]",
                "}",
                ""), out.toString());
    }

    @Test
    @DisplayName("empty containers stay on one line")
    void emitsEmptyContainersCompactly() throws IOException {
        StringWriter out = new StringWriter();
        JsonEmitter json = new JsonEmitter(out);
        json.beginObject().name("a").beginArray().endArray().name("b").beginObject().endObject().endObject();
        json.finish();
        assertEquals("{\n  \"a\": [],\n  \"b\": {}\n}\n", out.toString());
    }

    @Test
    @DisplayName("emitter output re-parses to an equal tree")
    void emitterAndParserAgree() throws IOException {
        StringWriter out = new StringWriter();
        JsonEmitter json = new JsonEmitter(out);
        json.beginObject();
        json.name("s").value("x\"y");
        json.name("i").value(-42L);
        json.name("b").value(true);
        json.name("nul").value((Boolean) null);
        json.name("arr").beginArray().value(1L).value("two").endArray();
        json.name("empty").beginArray().endArray();
        json.endObject();
        json.finish();

        Map<?, ?> tree = (Map<?, ?>) JsonParser.parse(out.toString());
        assertEquals("x\"y", tree.get("s"));
        assertEquals(Long.valueOf(-42L), tree.get("i"));
        assertEquals(Boolean.TRUE, tree.get("b"));
        assertNull(tree.get("nul"));
        assertEquals(List.of(1L, "two"), tree.get("arr"));
        assertTrue(((List<?>) tree.get("empty")).isEmpty());
    }
}
