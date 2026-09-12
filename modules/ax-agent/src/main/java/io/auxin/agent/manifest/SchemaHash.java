package io.auxin.agent.manifest;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * CONTRACTS section 1: <i>"schemaHash = SHA-256 of the class's sorted (name+desc) list."</i>
 *
 * <p>The contract fixes the inputs but not the byte-level encoding, so this class IS the
 * canonical definition until ax-static exists; ax-static must produce the identical bytes or
 * every class is skipped as {@code schemaHashMismatch}. Encoding, spelled out:
 *
 * <pre>
 *   entries = the class's probe-eligible methods sorted by (name, descriptor),
 *             lexicographically, exactly the ordering that assigns probe indices
 *   input   = for each entry: UTF-8(name + descriptor) followed by 0x0A
 *   hash    = lowercase hex of SHA-256(input)
 * </pre>
 *
 * <p>Note what is NOT in the hash: the class name, the probe indices, and — deliberately — any
 * byte of the class file. VALIDATION A14 defect 1: JaCoCo keys on CRC64 of the raw class file,
 * so a second agent transforming first silently breaks every merge.
 */
public final class SchemaHash {

    /**
     * Sorts by (name, descriptor) as a tuple — NOT by the concatenated string, which orders
     * differently whenever one method name is a prefix of another (e.g. {@code a} vs
     * {@code a$b}: '(' is 0x28 and '$' is 0x24).
     *
     * @param nameDesc each element is {name, descriptor}
     */
    public static String compute(List<String[]> nameDesc) {
        List<String[]> sorted = new java.util.ArrayList<String[]>(nameDesc);
        Collections.sort(sorted, COMPARATOR);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < sorted.size(); i++) {
                md.update(utf8(sorted.get(i)[0] + sorted.get(i)[1]));
                md.update((byte) '\n');
            }
            return hex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The single ordering that defines both the schema hash and the probe indices. */
    public static final Comparator<String[]> COMPARATOR = new Comparator<String[]>() {
        @Override
        public int compare(String[] a, String[] b) {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        }
    };

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private SchemaHash() { throw new AssertionError(); }
}
