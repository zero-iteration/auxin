package dev.auxin.manifest;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * The fingerprint that lets the agent detect that a class is not the class the probe indices were
 * computed for.
 *
 * <p>WHY a hash of the <em>member list</em> and not of the class bytes: CONTRACTS section 1 fixes
 * identity at {@code (buildSha, className, methodDesc)}. Class bytes change on every recompile --
 * a different javac, a different debug flag, a different jar timestamp -- while the probe layout
 * does not. Hashing the bytes would make the agent refuse to probe perfectly valid classes; hashing
 * the member list makes it refuse exactly when a member was added, removed or renamed, which is
 * exactly when the indices would shift underneath us (A14).
 *
 * <p>Canonical form, which both the build and the agent must reproduce bit for bit:
 * <ol>
 *   <li>take {@code name + desc} for every probed method;</li>
 *   <li>sort those strings with {@link String#compareTo} -- code-unit order, never a locale
 *       {@code Collator}, so the value is identical on every machine;</li>
 *   <li>join with {@code '\n'}, with a trailing {@code '\n'} after the last element;</li>
 *   <li>SHA-256 the UTF-8 bytes; render lowercase hex.</li>
 * </ol>
 * An empty member list hashes the empty string, which is a stable, well-defined value.
 */
public final class SchemaHash {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SchemaHash() {
    }

    /** Computes the schema hash of a class entry from its probed methods. */
    public static String of(ClassEntry entry) {
        List<String> nameAndDescs = new ArrayList<String>(entry.methods().size());
        for (MethodEntry m : entry.methods()) {
            nameAndDescs.add(m.nameAndDesc());
        }
        return ofNameAndDescs(nameAndDescs);
    }

    /**
     * Computes the schema hash from raw {@code name + desc} strings.
     *
     * <p>This overload is the agent-facing entry point: at transform time the agent has ASM's
     * visited members, not {@link MethodEntry} objects, and it must be able to compute the hash
     * without reconstructing the manifest model.
     */
    public static String ofNameAndDescs(Collection<String> nameAndDescs) {
        List<String> sorted = new ArrayList<String>(nameAndDescs);
        Collections.sort(sorted, NAME_THEN_DESC);
        StringBuilder canonical = new StringBuilder();
        for (String s : sorted) {
            canonical.append(s).append('\n');
        }
        return hex(sha256(utf8(canonical.toString())));
    }

    /**
     * Orders {@code name+descriptor} strings by the TUPLE {@code (name, descriptor)}, splitting at
     * the first {@code '('} -- which is unambiguous because a JVM method name may not contain
     * {@code '('} and every descriptor begins with it.
     *
     * <p>This MUST match {@link ProbeIndex}'s ordering. Sorting the concatenated string instead
     * gives a DIFFERENT order whenever one method name is a prefix of another (e.g. {@code pick}
     * vs {@code pick$inner}: {@code '$'}=0x24 sorts before {@code '('}=0x28, so the concatenated
     * form reverses them). A divergence here makes the agent's computed hash differ from the
     * manifest's, so every class is skipped and coverage is silently empty -- the exact
     * silent-zero failure mode this project exists to avoid.
     */
    static final Comparator<String> NAME_THEN_DESC = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            int ia = a.indexOf('(');
            int ib = b.indexOf('(');
            String na = ia < 0 ? a : a.substring(0, ia);
            String nb = ib < 0 ? b : b.substring(0, ib);
            int c = na.compareTo(nb);
            if (c != 0) {
                return c;
            }
            String da = ia < 0 ? "" : a.substring(ia);
            String db = ib < 0 ? "" : b.substring(ib);
            return da.compareTo(db);
        }
    };

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required by the JVM specification", e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0f];
        }
        return new String(out);
    }
}
