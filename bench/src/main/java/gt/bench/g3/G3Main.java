package ax.bench.g3;

import ax.bench.agent.BenchAgent;
import ax.bench.rt.GtBenchRuntime;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeSet;

/**
 * GATE G3 - condy install / strip correctness, automating the E2 proof as an assertion suite.
 *
 * Run as:  java -javaagent:ax-bench.jar=g3 -cp ax-bench.jar ax.bench.g3.G3Main
 * Exits non-zero on any failed assertion.
 */
public final class G3Main {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("== G3 on " + System.getProperty("java.vm.name") + " "
                           + System.getProperty("java.version")
                           + " (" + System.getProperty("os.arch") + ") ==");

        String owner = "gt/bench/g3/Subject";
        Subject s = new Subject();          // triggers class load -> installer -> fires the <init> probe
        Map<String, Integer> idx = BenchAgent.INDEX.get(owner);
        check("installer ran at class load and published a probe index", idx != null && !idx.isEmpty());
        if (idx == null) { finish(); return; }
        check("probe array materialised via condy BSM", GtBenchRuntime.probes(owner) != null);
        check("probe array sized from the manifest (" + idx.size() + ")",
              GtBenchRuntime.probes(owner).length == idx.size());

        // ---- 1. the right probes flip -------------------------------------------------------
        check("alpha() == 11", s.alpha() == 11);
        check("gamma() == 33", s.gamma() == 33);
        boolean[] p = GtBenchRuntime.probes(owner);
        check("probe[<init>] set",  p[idx.get("<init>()V")]);
        check("probe[alpha] set",   p[idx.get("alpha()I")]);
        check("probe[gamma] set",   p[idx.get("gamma()I")]);
        check("probe[beta] CLEAR",  !p[idx.get("beta()I")]);
        check("probe[delta] CLEAR", !p[idx.get("delta(I)I")]);
        check("probe[epsilon] CLEAR", !p[idx.get("epsilon()Ljava/lang/String;")]);
        System.out.println("   probes[Subject] = " + GtBenchRuntime.render(owner) + "  (before strip)");

        // ---- 2. schema snapshot before retransform ------------------------------------------
        String membersBefore = reflectMembers(Subject.class);
        String bytesMembersBefore = classFileMembers(BenchAgent.G3Record.installed.get(owner));

        // ---- 3. retransform to strip --------------------------------------------------------
        BenchAgent.G3Stripper.armed = true;
        BenchAgent.INST.retransformClasses(Subject.class);
        check("stripper removed every probe (" + BenchAgent.G3Stripper.lastRemoved + " of " + idx.size() + ")",
              BenchAgent.G3Stripper.lastRemoved == idx.size());

        // ---- 4. a method called AFTER the strip must record NOTHING --------------------------
        String snapshot = GtBenchRuntime.render(owner);
        check("beta() == 22 after strip", s.beta() == 22);
        check("delta(7) == 21 after strip", s.delta(7) == 21);
        check("epsilon() == eps after strip", "eps".equals(Subject.epsilon()));
        check("new Subject() still constructs after strip", new Subject().field1 == 7);
        check("PROBES UNCHANGED after post-strip calls (" + snapshot + " -> "
              + GtBenchRuntime.render(owner) + ")",
              snapshot.equals(GtBenchRuntime.render(owner)));
        check("probe[beta] STILL CLEAR - beta ran and recorded nothing",
              !GtBenchRuntime.probes(owner)[idx.get("beta()I")]);

        // ---- 5. all methods still work ------------------------------------------------------
        check("alpha() still 11", s.alpha() == 11);
        check("gamma() still 33", s.gamma() == 33);
        check("field3() still 'three'", "three".equals(s.field3()));
        check("static field2 still 9", Subject.field2 == 9L);

        // ---- 6. no field or method added or removed during retransform -----------------------
        String membersAfter = reflectMembers(Subject.class);
        check("reflection member set identical across retransform", membersBefore.equals(membersAfter));
        String bytesMembersAfter = classFileMembers(BenchAgent.G3Record.stripped.get(owner));
        check("class-file member set identical across retransform",
              bytesMembersBefore.equals(bytesMembersAfter));
        if (!membersBefore.equals(membersAfter)) {
            System.out.println("   before: " + membersBefore);
            System.out.println("   after : " + membersAfter);
        }

        // ---- 7. bonus: is the raw [Z condy descriptor legal on this JDK? ----------------------
        try {
            SubjectRaw r = new SubjectRaw();
            int v = r.one() + r.two();
            boolean[] rp = GtBenchRuntime.probes("gt/bench/g3/SubjectRaw");
            System.out.println("   RAW-DESCRIPTOR PROBE: WORKS on this JDK (sum=" + v
                               + ", probes=" + GtBenchRuntime.render("gt/bench/g3/SubjectRaw")
                               + ") -> condy may be declared [Z, saving the 3-byte CHECKCAST per site");
            check("raw [Z condy recorded probes", rp != null && v == 3);
        } catch (Throwable t) {
            System.out.println("   RAW-DESCRIPTOR PROBE: REJECTED on this JDK -> keep JaCoCo's "
                               + "Ljava/lang/Object; + CHECKCAST workaround (JDK-8216970). " + t);
        }

        check("zero transformer failures", BenchAgent.transformFailures.get() == 0);
        finish();
    }

    static void finish() {
        System.out.println(failures == 0 ? "== G3 PASS ==" : "== G3 FAIL (" + failures + ") ==");
        if (failures != 0) System.exit(1);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + what);
        if (!ok) failures++;
    }

    static String reflectMembers(Class<?> c) {
        TreeSet<String> out = new TreeSet<>();
        for (Field f : c.getDeclaredFields()) out.add("F " + f.getName() + " " + f.getType().getName());
        for (Method m : c.getDeclaredMethods()) out.add("M " + m.getName() + " " + m.toString());
        for (Constructor<?> k : c.getDeclaredConstructors()) out.add("C " + k.toString());
        return out.toString();
    }

    static String classFileMembers(byte[] cf) {
        if (cf == null) return "(absent)";
        TreeSet<String> out = new TreeSet<>();
        new ClassReader(cf).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                out.add("F " + n + " " + d); return null;
            }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                out.add("M " + n + d); return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out.toString();
    }
}
