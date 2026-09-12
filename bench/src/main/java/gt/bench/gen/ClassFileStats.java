package ax.bench.gen;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal class-file walker. Exists because ASM does not expose raw attribute byte counts, and
 * G1 has to report the thing that actually drives load-time verification CPU: how many bytes of
 * Code and how many bytes of StackMapTable the JVM verifier has to chew through.
 */
public final class ClassFileStats {

    public int fileBytes;
    public int constantPoolEntries;
    public int methodCount;
    public int codeBytes;
    public int stackMapTableBytes;
    public int stackMapFrames;
    public final Map<String, Integer> codeLenByMethod = new LinkedHashMap<>();

    private ClassFileStats() {}

    public static ClassFileStats of(byte[] cf) {
        ClassFileStats st = new ClassFileStats();
        st.fileBytes = cf.length;
        ByteBuffer b = ByteBuffer.wrap(cf);
        b.getInt();                 // magic
        b.getShort(); b.getShort(); // minor, major
        int cpCount = u2(b);
        String[] utf8 = new String[cpCount];
        st.constantPoolEntries = cpCount - 1;
        for (int i = 1; i < cpCount; i++) {
            int tag = b.get() & 0xFF;
            switch (tag) {
                case 1: { int len = u2(b); byte[] s = new byte[len]; b.get(s); utf8[i] = new String(s, java.nio.charset.StandardCharsets.UTF_8); break; }
                case 7: case 8: case 16: case 19: case 20: skip(b, 2); break;
                case 15: skip(b, 3); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: skip(b, 4); break;
                case 5: case 6: skip(b, 8); i++; break;
                default: throw new IllegalStateException("bad cp tag " + tag + " at " + i);
            }
        }
        skip(b, 6);                 // access, this, super
        int ifs = u2(b); skip(b, ifs * 2);
        int fields = u2(b);
        for (int i = 0; i < fields; i++) { skip(b, 6); skipAttributes(b, utf8, st, null); }
        int methods = u2(b);
        st.methodCount = methods;
        for (int i = 0; i < methods; i++) {
            skip(b, 2);
            String name = utf8[u2(b)];
            String desc = utf8[u2(b)];
            skipAttributes(b, utf8, st, name + desc);
        }
        return st;
    }

    private static void skipAttributes(ByteBuffer b, String[] utf8, ClassFileStats st, String owner) {
        int n = u2(b);
        for (int i = 0; i < n; i++) {
            String an = utf8[u2(b)];
            int len = b.getInt();
            int end = b.position() + len;
            if ("Code".equals(an)) {
                skip(b, 4);                       // max_stack, max_locals
                int codeLen = b.getInt();
                st.codeBytes += codeLen;
                if (owner != null) st.codeLenByMethod.put(owner, codeLen);
                skip(b, codeLen);
                int ex = u2(b); skip(b, ex * 8);
                skipAttributes(b, utf8, st, null);
            } else if ("StackMapTable".equals(an)) {
                st.stackMapTableBytes += len + 6;  // attribute payload + name index + length
                st.stackMapFrames += u2(b);
                b.position(end);
            } else {
                b.position(end);
            }
            b.position(end);
        }
    }

    private static int u2(ByteBuffer b) { return b.getShort() & 0xFFFF; }
    private static void skip(ByteBuffer b, int n) { b.position(b.position() + n); }
}
