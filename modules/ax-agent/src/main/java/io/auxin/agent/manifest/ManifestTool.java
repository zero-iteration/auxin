package io.auxin.agent.manifest;

import io.auxin.agent.util.GeneratedNames;
import io.auxin.agent.util.Json;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TEMPORARY — this belongs in ax-static, which does not exist yet in modules/.
 *
 * <p>It implements exactly the CONTRACTS section 1 rules the agent depends on, so that the agent
 * can be exercised end to end today, and so that ax-static has an executable reference for the
 * two things that must match byte for byte or every class is skipped:
 * the probe-index ordering and {@link SchemaHash}.
 *
 * <p>It does NOT implement entry points or the call graph — those stay empty arrays here.
 *
 * <pre>
 *   java -cp ax-agent.jar io.auxin.agent.manifest.ManifestTool \
 *        &lt;classesDir&gt; &lt;out.json&gt; [--artifact=name] [--buildSha=sha] \
 *        [--tier2=com.acme.Svc#handle,com.acme.web.*#*]
 * </pre>
 */
public final class ManifestTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ManifestTool <classesDir> <out.json> "
                    + "[--artifact=x] [--buildSha=y] [--tier2=Class#method,...]");
            System.exit(2);
        }
        File root = new File(args[0]);
        File out = new File(args[1]);
        String artifact = root.getName();
        String buildSha = "dev";
        List<String> tier2 = new ArrayList<String>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--artifact=")) artifact = args[i].substring(11);
            else if (args[i].startsWith("--buildSha=")) buildSha = args[i].substring(11);
            else if (args[i].startsWith("--tier2=")) {
                for (String p : args[i].substring(8).split(",")) {
                    if (p.trim().length() > 0) tier2.add(p.trim());
                }
            }
        }

        List<File> classFiles = new ArrayList<File>();
        collect(root, classFiles);
        Collections.sort(classFiles, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getPath().compareTo(b.getPath()); }
        });

        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("{\n  \"schemaVersion\": 2,\n  \"buildSha\": ");
        Json.writeString(sb, buildSha);
        sb.append(",\n  \"artifact\": ");
        Json.writeString(sb, artifact);
        sb.append(",\n  \"generatedAt\": ");
        Json.writeString(sb, iso8601(System.currentTimeMillis()));
        sb.append(",\n  \"classes\": [");

        int emitted = 0;
        for (int i = 0; i < classFiles.size(); i++) {
            String entry = classEntry(classFiles.get(i), tier2);
            if (entry == null) continue;
            if (emitted++ > 0) sb.append(',');
            sb.append("\n    ").append(entry);
        }
        sb.append("\n  ],\n  \"entryPoints\": [],\n  \"callEdges\": []\n}\n");

        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(sb.toString().getBytes("UTF-8"));
        } finally {
            fos.close();
        }
        System.out.println("[manifest] " + emitted + " class(es) -> " + out.getPath());
    }

    private static String classEntry(File f, List<String> tier2Patterns) throws Exception {
        ClassNode cn = new ClassNode();
        InputStream in = new FileInputStream(f);
        try {
            // keep debug info: CONTRACTS section 1 carries sourceFile and a line number
            new ClassReader(in).accept(cn, ClassReader.SKIP_FRAMES);
        } finally {
            in.close();
        }

        List<MethodNode> eligible = new ArrayList<MethodNode>();
        List<String[]> keys = new ArrayList<String[]>();
        for (int i = 0; i < cn.methods.size(); i++) {
            MethodNode m = cn.methods.get(i);
            if (!Eligibility.isProbeEligible(m.access, m.name)) continue;
            eligible.add(m);
            keys.add(new String[]{m.name, m.desc});
        }
        if (eligible.isEmpty()) return null;
        // "Runtime-generated classes are NEVER inventoried or probed" (CONTRACTS section 1)
        if (GeneratedNames.isGenerated(cn.name)) return null;

        // CONTRACTS section 1: idx is assigned by sorting (className, methodName, descriptor)
        // lexicographically and numbering from 0 WITHIN EACH CLASS.
        Collections.sort(eligible, new java.util.Comparator<MethodNode>() {
            @Override public int compare(MethodNode a, MethodNode b) {
                int c = a.name.compareTo(b.name);
                return c != 0 ? c : a.desc.compareTo(b.desc);
            }
        });

        String dotted = cn.name.replace('/', '.');
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        first = Json.key(sb, first, "name");
        Json.writeString(sb, dotted);
        first = Json.key(sb, first, "sourceFile");
        Json.writeString(sb, cn.sourceFile == null ? "" : cn.sourceFile);
        first = Json.key(sb, first, "probeCount");
        sb.append(eligible.size());
        first = Json.key(sb, first, "schemaHash");
        Json.writeString(sb, SchemaHash.compute(keys));
        first = Json.key(sb, first, "isPublicApi");
        // Conservative: ax-static resolves the real published API surface. Until then, every
        // public type is treated as API surface, which makes it UNKNOWN rather than deletable
        // (C47: coverage-only debloating broke 18.5% of downstream clients).
        sb.append((cn.access & Opcodes.ACC_PUBLIC) != 0);
        first = Json.key(sb, first, "methods");
        sb.append('[');
        for (int i = 0; i < eligible.size(); i++) {
            MethodNode m = eligible.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            boolean mf = true;
            mf = Json.key(sb, mf, "idx");
            sb.append(i);
            mf = Json.key(sb, mf, "name");
            Json.writeString(sb, m.name);
            mf = Json.key(sb, mf, "desc");
            Json.writeString(sb, m.desc);
            mf = Json.key(sb, mf, "line");
            sb.append(firstLine(m));
            mf = Json.key(sb, mf, "access");
            Json.writeString(sb, access(m.access));
            mf = Json.key(sb, mf, "synthetic");
            sb.append(false);
            mf = Json.key(sb, mf, "tier2");
            sb.append(matchesAny(dotted, m.name, tier2Patterns));
            mf = Json.key(sb, mf, "dynamicallyObservable");
            sb.append(dynamicallyObservable(m));
            mf = Json.key(sb, mf, "shortCircuitable");
            sb.append(shortCircuitable(cn, m));
            sb.append('}');
        }
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    /**
     * C51. TOSEM 2022: "Primitive constants, custom exceptions, and single-instruction
     * methods... cannot be covered dynamically." Conservative on purpose — marking a method
     * unobservable costs recall; marking a genuinely unobservable method observable produces a
     * probe that never flips, which the analysis would read as dead code.
     */
    static boolean dynamicallyObservable(MethodNode m) {
        List<AbstractInsnNode> real = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) real.add(insn);
        }
        if (real.isEmpty()) return false;
        if (real.size() == 1) return false;                       // empty body: just RETURN
        if (real.size() == 2 && isConstant(real.get(0)) && isReturn(real.get(1))) return false;
        return true;
    }

    /**
     * C59. A Spring proxy can short-circuit before the target body runs, so a warm
     * {@code @Cacheable} method may never execute while its feature is heavily used. Detected
     * from annotations on the method or its declaring class, exactly as CONTRACTS section 1
     * specifies. Absent field means "assume true (safe)", so emitting false is only correct
     * when we have actually looked — which is what this does.
     */
    static boolean shortCircuitable(ClassNode cn, MethodNode m) {
        return hasAny(cn.visibleAnnotations) || hasAny(cn.invisibleAnnotations)
                || hasAny(m.visibleAnnotations) || hasAny(m.invisibleAnnotations);
    }

    private static final String[] SHORT_CIRCUIT_ANNOTATIONS = {
            "Cacheable", "CachePut", "CacheResult", "Caching",
            "CircuitBreaker", "Retryable", "Recover", "HystrixCommand",
            "Bulkhead", "RateLimiter", "TimeLimiter", "Transactional"
    };

    private static boolean hasAny(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (int i = 0; i < annotations.size(); i++) {
            String simple = simpleName(annotations.get(i).desc);
            for (int j = 0; j < SHORT_CIRCUIT_ANNOTATIONS.length; j++) {
                if (SHORT_CIRCUIT_ANNOTATIONS[j].equals(simple)) return true;
            }
        }
        return false;
    }

    private static String simpleName(String desc) {
        int end = desc.endsWith(";") ? desc.length() - 1 : desc.length();
        int start = Math.max(desc.lastIndexOf('/'), desc.lastIndexOf('$')) + 1;
        if (start == 0 && desc.startsWith("L")) start = 1;
        return desc.substring(start, end);
    }

    private static boolean isConstant(AbstractInsnNode n) {
        int op = n.getOpcode();
        if (op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1) return true;
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return true;
        return op == Opcodes.LDC && n instanceof LdcInsnNode;
    }

    private static boolean isReturn(AbstractInsnNode n) {
        int op = n.getOpcode();
        return op >= Opcodes.IRETURN && op <= Opcodes.RETURN;
    }

    private static boolean matchesAny(String cls, String method, List<String> patterns) {
        for (int i = 0; i < patterns.size(); i++) {
            String p = patterns.get(i);
            int hash = p.indexOf('#');
            String cp = hash < 0 ? p : p.substring(0, hash);
            String mp = hash < 0 ? "*" : p.substring(hash + 1);
            boolean classOk = cp.endsWith("*")
                    ? cls.startsWith(cp.substring(0, cp.length() - 1))
                    : cls.equals(cp);
            boolean methodOk = mp.equals("*") || mp.equals(method);
            if (classOk && methodOk) return true;
        }
        return false;
    }

    private static int firstLine(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.LineNumberNode) {
                return ((org.objectweb.asm.tree.LineNumberNode) insn).line;
            }
        }
        return -1;
    }

    private static String access(int a) {
        if ((a & Opcodes.ACC_PUBLIC) != 0) return "public";
        if ((a & Opcodes.ACC_PROTECTED) != 0) return "protected";
        if ((a & Opcodes.ACC_PRIVATE) != 0) return "private";
        return "package";
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].isDirectory()) collect(kids[i], out);
            else if (kids[i].getName().endsWith(".class")
                    && !kids[i].getName().equals("module-info.class")) out.add(kids[i]);
        }
    }

    private static String iso8601(long ms) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private ManifestTool() { throw new AssertionError(); }
}
