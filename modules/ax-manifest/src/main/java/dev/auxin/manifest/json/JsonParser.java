package dev.auxin.manifest.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, strict, recursive-descent JSON parser producing a plain Java tree.
 *
 * <p>WHY this exists at all: {@code ax-agent} is loaded into someone else's application. Pulling
 * Jackson or Gson onto the agent's classpath risks a version clash with whatever the host already
 * uses, and that clash surfaces as a {@link NoSuchMethodError} inside a {@code ClassFileTransformer}
 * -- which the JVM swallows silently. A few hundred lines of hand-written parser is cheaper than
 * that failure mode.
 *
 * <p>WHY the tree is plain {@code Map}/{@code List}: the manifest schema is frozen and small, so
 * the mapping from tree to typed objects is done once, explicitly, in
 * {@code ManifestReader}. That keeps the parser free of any knowledge of the schema and makes the
 * schema-validation errors readable, rather than hiding them inside reflective binding.
 *
 * <p>Mapping: object -&gt; {@link LinkedHashMap} (insertion ordered), array -&gt; {@link ArrayList},
 * string -&gt; {@link String}, integral number -&gt; {@link Long}, fractional number -&gt;
 * {@link Double}, {@code true}/{@code false} -&gt; {@link Boolean}, {@code null} -&gt; {@code null}.
 *
 * <p>Deliberately strict: no comments, no trailing commas, no single quotes, no NaN/Infinity,
 * and trailing content after the top-level value is an error. A manifest that only <em>almost</em>
 * parses is a manifest we must not act on.
 */
public final class JsonParser {

    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;

    private JsonParser(String src) {
        this.src = src;
    }

    /**
     * Parses a complete JSON document and returns its root value.
     *
     * @throws JsonSyntaxException if the document is malformed or has trailing content
     */
    public static Object parse(String json) {
        JsonParser p = new JsonParser(json);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (p.pos != p.src.length()) {
            throw p.fail("trailing content after top-level value");
        }
        return value;
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw fail("nesting deeper than " + MAX_DEPTH);
        }
        char c = peek();
        switch (c) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return readString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject(int depth) {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Object value = readValue(depth + 1);
            if (object.containsKey(key)) {
                throw fail("duplicate key '" + key + "'");
            }
            object.put(key, value);
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw fail("expected ',' or '}' but found '" + c + "'");
            }
        }
    }

    private List<Object> readArray(int depth) {
        expect('[');
        List<Object> array = new ArrayList<Object>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                throw fail("expected ',' or ']' but found '" + c + "'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                sb.append(readEscape());
            } else if (c < 0x20) {
                throw fail("unescaped control character U+" + Integer.toHexString(c));
            } else {
                sb.append(c);
            }
        }
    }

    private char readEscape() {
        char c = next();
        switch (c) {
            case '"':
                return '"';
            case '\\':
                return '\\';
            case '/':
                return '/';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                return readUnicodeEscape();
            default:
                throw fail("invalid escape '\\" + c + "'");
        }
    }

    private char readUnicodeEscape() {
        if (pos + 4 > src.length()) {
            throw fail("truncated \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(src.charAt(pos + i), 16);
            if (digit < 0) {
                throw fail("invalid hex digit in \\u escape");
            }
            value = (value << 4) | digit;
        }
        pos += 4;
        return (char) value;
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        readDigits();
        boolean fractional = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            fractional = true;
            pos++;
            readDigits();
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            fractional = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            readDigits();
        }
        String text = src.substring(start, pos);
        try {
            // Integral numbers stay integral: the manifest's idx / line / probeCount fields are
            // counts, and a round trip through double would silently lose precision on large ids.
            return fractional ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new JsonSyntaxException("not a number: '" + text + "'", start);
        }
    }

    private void readDigits() {
        int start = pos;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
            pos++;
        }
        if (pos == start) {
            throw fail("expected a digit");
        }
    }

    private void expectLiteral(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw fail("expected '" + literal + "'");
        }
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw fail("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw new JsonSyntaxException("expected '" + expected + "' but found '" + c + "'", pos - 1);
        }
    }

    private JsonSyntaxException fail(String message) {
        return new JsonSyntaxException(message, pos);
    }
}
