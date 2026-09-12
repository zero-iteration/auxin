package dev.auxin.manifest.json;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A minimal streaming JSON writer with deterministic, diff-friendly formatting.
 *
 * <p>WHY hand-written: same reason as {@link JsonParser} -- the agent side of this contract must
 * not drag a JSON library into a host application's classpath.
 *
 * <p>WHY pretty-printed rather than compact: {@code auxin-manifest.json} is a build artifact
 * that gets checked into or diffed against previous builds to answer "what changed in the probe
 * layout?". A one-line document makes that diff unreadable, and an unreadable diff is how a probe
 * index shift goes unnoticed (A14).
 *
 * <p>The emitter enforces structural correctness (no value without a name inside an object, no
 * unbalanced containers) so that a bug in the mapping layer fails loudly here instead of producing
 * a document that parses but means something else.
 */
public final class JsonEmitter {

    private enum Scope { OBJECT, ARRAY }

    private final Writer out;
    private final String indentUnit;
    private final Deque<Scope> scopes = new ArrayDeque<Scope>();
    private boolean needsComma;
    private boolean nameWritten;

    public JsonEmitter(Writer out) {
        this(out, "  ");
    }

    public JsonEmitter(Writer out, String indentUnit) {
        if (out == null) {
            throw new NullPointerException("out");
        }
        this.out = out;
        this.indentUnit = indentUnit == null ? "" : indentUnit;
    }

    public JsonEmitter beginObject() throws IOException {
        prepareValue();
        out.write('{');
        scopes.push(Scope.OBJECT);
        needsComma = false;
        return this;
    }

    public JsonEmitter endObject() throws IOException {
        return endScope(Scope.OBJECT, '}');
    }

    public JsonEmitter beginArray() throws IOException {
        prepareValue();
        out.write('[');
        scopes.push(Scope.ARRAY);
        needsComma = false;
        return this;
    }

    public JsonEmitter endArray() throws IOException {
        return endScope(Scope.ARRAY, ']');
    }

    public JsonEmitter name(String name) throws IOException {
        if (scopes.peek() != Scope.OBJECT) {
            throw new IllegalStateException("name() outside of an object");
        }
        if (nameWritten) {
            throw new IllegalStateException("name() called twice without a value");
        }
        if (needsComma) {
            out.write(',');
        }
        newlineAndIndent(scopes.size());
        writeQuoted(name);
        out.write(':');
        out.write(' ');
        nameWritten = true;
        return this;
    }

    public JsonEmitter value(String value) throws IOException {
        prepareValue();
        if (value == null) {
            out.write("null");
        } else {
            writeQuoted(value);
        }
        return this;
    }

    public JsonEmitter value(long value) throws IOException {
        prepareValue();
        out.write(Long.toString(value));
        return this;
    }

    public JsonEmitter value(boolean value) throws IOException {
        prepareValue();
        out.write(value ? "true" : "false");
        return this;
    }

    /** Emits a tri-state boolean; {@code null} becomes JSON {@code null}, never {@code false}. */
    public JsonEmitter value(Boolean value) throws IOException {
        if (value == null) {
            return nullValue();
        }
        return value(value.booleanValue());
    }

    public JsonEmitter nullValue() throws IOException {
        prepareValue();
        out.write("null");
        return this;
    }

    /** Writes a trailing newline and flushes. Deliberately does not close the underlying writer. */
    public void finish() throws IOException {
        if (!scopes.isEmpty()) {
            throw new IllegalStateException("unbalanced JSON document: " + scopes.size() + " open scope(s)");
        }
        out.write('\n');
        out.flush();
    }

    private JsonEmitter endScope(Scope expected, char close) throws IOException {
        if (scopes.peek() != expected) {
            throw new IllegalStateException("mismatched close of " + expected);
        }
        if (nameWritten) {
            throw new IllegalStateException("object closed with a dangling name");
        }
        boolean hadMembers = needsComma;
        scopes.pop();
        if (hadMembers) {
            // Members were indented at the depth that included this scope; the closing token sits
            // one level out, which is exactly the depth remaining after the pop.
            newlineAndIndent(scopes.size());
        }
        out.write(close);
        needsComma = true;
        return this;
    }

    private void prepareValue() throws IOException {
        Scope scope = scopes.peek();
        if (scope == Scope.OBJECT) {
            if (!nameWritten) {
                throw new IllegalStateException("value without a preceding name()");
            }
            nameWritten = false;
        } else if (scope == Scope.ARRAY) {
            if (needsComma) {
                out.write(',');
            }
            newlineAndIndent(scopes.size());
        } else if (needsComma) {
            throw new IllegalStateException("more than one top-level value");
        }
        needsComma = true;
    }

    private void newlineAndIndent(int depth) throws IOException {
        if (indentUnit.isEmpty()) {
            return;
        }
        out.write('\n');
        for (int i = 0; i < depth; i++) {
            out.write(indentUnit);
        }
    }

    private void writeQuoted(String s) throws IOException {
        out.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.write("\\\"");
                    break;
                case '\\':
                    out.write("\\\\");
                    break;
                case '\n':
                    out.write("\\n");
                    break;
                case '\r':
                    out.write("\\r");
                    break;
                case '\t':
                    out.write("\\t");
                    break;
                case '\b':
                    out.write("\\b");
                    break;
                case '\f':
                    out.write("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.write("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) {
                            out.write('0');
                        }
                        out.write(hex);
                    } else {
                        out.write(c);
                    }
            }
        }
        out.write('"');
    }
}
