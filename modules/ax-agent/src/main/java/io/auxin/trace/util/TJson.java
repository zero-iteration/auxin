package io.auxin.trace.util;

/**
 * A minimal JSON writer. No Jackson, no Gson, no dependency (PLAN-v2 "Non-negotiables in code":
 * shaded ASM only).
 *
 * <p>Append-only, single-threaded, used on the trace-builder thread and never on an application
 * thread. Every string that reaches {@link #str} is escaped, including the control-character
 * range, because a class name or an enum constant can in principle contain anything the JVM
 * allows in an identifier.
 */
public final class TJson {

    private final StringBuilder sb;
    private boolean needComma;

    public TJson() { this(4096); }

    public TJson(int capacity) { this.sb = new StringBuilder(capacity); }

    public TJson objectStart() { sep(); sb.append('{'); needComma = false; return this; }

    public TJson objectEnd() { sb.append('}'); needComma = true; return this; }

    public TJson arrayStart() { sep(); sb.append('['); needComma = false; return this; }

    public TJson arrayEnd() { sb.append(']'); needComma = true; return this; }

    public TJson key(String k) { sep(); str(k); sb.append(':'); needComma = false; return this; }

    public TJson objectStart(String k) { return key(k).objectStart(); }

    public TJson arrayStart(String k) { return key(k).arrayStart(); }

    public TJson field(String k, String v) { key(k); value(v); return this; }

    public TJson field(String k, long v) { key(k); value(v); return this; }

    public TJson field(String k, double v) { key(k); value(v); return this; }

    public TJson field(String k, boolean v) { key(k); value(v); return this; }

    public TJson value(String v) {
        sep();
        if (v == null) sb.append("null");
        else str(v);
        needComma = true;
        return this;
    }

    public TJson value(long v) { sep(); sb.append(v); needComma = true; return this; }

    public TJson value(boolean v) { sep(); sb.append(v); needComma = true; return this; }

    public TJson value(double v) {
        sep();
        // JSON has no NaN/Infinity. Emitting one produces a body a strict parser rejects, so a
        // non-finite observation becomes null -- an absent number, not a corrupt document.
        if (Double.isNaN(v) || Double.isInfinite(v)) sb.append("null");
        else sb.append(v);
        needComma = true;
        return this;
    }

    public TJson raw(String json) { sep(); sb.append(json); needComma = true; return this; }

    private void sep() { if (needComma) sb.append(','); }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            sb.append("0123456789abcdef".charAt((c >>> shift) & 0xF));
                        }
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    public int length() { return sb.length(); }

    @Override
    public String toString() { return sb.toString(); }
}
