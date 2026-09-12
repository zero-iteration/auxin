package io.auxin.agent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer. Exists because the agent may not depend on Jackson or anything
 * else (PLAN-v2 "Non-negotiables in code": shaded ASM only).
 *
 * <p>Parsing happens once, in premain, off the application's hot path. Writing happens on the
 * drain thread only. Neither is on an application thread, so allocation here is free.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** @return Map, List, String, Double, Boolean or null. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> o, String k, String def) {
        Object v = o == null ? null : o.get(k);
        return v instanceof String ? (String) v : def;
    }

    public static int num(Map<String, Object> o, String k, int def) {
        Object v = o == null ? null : o.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    /** Tri-state: null when the key is absent, so C51's "flag present and false" is expressible. */
    public static Boolean bool(Map<String, Object> o, String k) {
        Object v = o == null ? null : o.get(k);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        pos++; // {
        ws();
        if (peek() == '}') { pos++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (peek() != ':') throw err("expected ':'");
            pos++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            pos++;
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        pos++; // [
        ws();
        if (peek() == ']') { pos++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            pos++;
            if (c == ']') return l;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String string() {
        if (peek() != '"') throw err("expected string");
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw err("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
        if (start == pos) throw err("expected value");
        return Double.valueOf(src.substring(start, pos));
    }

    private void expect(String lit) {
        if (!src.startsWith(lit, pos)) throw err("expected " + lit);
        pos += lit.length();
    }

    private char peek() {
        if (pos >= src.length()) throw err("unexpected end of input");
        return src.charAt(pos);
    }

    private void ws() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON: " + msg + " at offset " + pos);
    }

    // ---------------- writing ----------------

    /** Appends a quoted, escaped JSON string. */
    public static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Appends {@code "key":} (with a leading comma when {@code first} is false). */
    public static boolean key(StringBuilder sb, boolean first, String k) {
        if (!first) sb.append(',');
        writeString(sb, k);
        sb.append(':');
        return false;
    }

    private Json() { throw new AssertionError(); }
}
