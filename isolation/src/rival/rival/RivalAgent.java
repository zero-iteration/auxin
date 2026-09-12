package rival;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A second agent that claims {@code java.lang.$Auxin} for itself.
 *
 * <p>The name is a single-owner, JVM-global resource: whoever calls {@code defineClass} first
 * owns it, and the loser gets a {@code LinkageError: duplicate class definition}. Two things
 * have to be true for the design to be safe, and this agent exists to test both:
 * <ol>
 *   <li>when the rival wins, ax-agent must DETECT it, report
 *       {@code alreadyDefinedByAnotherAgent}, and degrade to skipping classes it cannot reach --
 *       never throw, never break application code;</li>
 *   <li>when ax-agent wins, the rival's own failure must stay inside the rival.</li>
 * </ol>
 *
 * <p>The class file is hand-assembled rather than generated with ASM so that this agent shares
 * no code at all with the implementation under test.
 */
public final class RivalAgent {

    public static void premain(String args, Instrumentation inst) {
        if ("tamper".equals(System.getProperty("rival.mode"))) {
            tamper();
            return;
        }
        try {
            Module javaBase = Object.class.getModule();
            Module self = RivalAgent.class.getModule();
            Map<String, Set<Module>> opens =
                    Collections.singletonMap("java.lang", Collections.singleton(self));
            inst.redefineModule(javaBase, Collections.<Module>emptySet(),
                    Collections.<String, Set<Module>>emptyMap(), opens,
                    Collections.<Class<?>>emptySet(),
                    Collections.<Class<?>, java.util.List<Class<?>>>emptyMap());

            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(Object.class, MethodHandles.lookup());
            Class<?> c = lookup.defineClass(bridgeClassFile());
            c.getField("data").set(null, new RivalData());
            System.out.println("[rival] CLAIMED java.lang.$Auxin (loader="
                    + c.getClassLoader() + ")");
        } catch (Throwable t) {
            // Losing the race is the other half of the test, and it must be survivable.
            System.out.println("[rival] could NOT claim java.lang.$Auxin: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * The other shape of the same problem: do not fight for the NAME, take the CONTENTS.
     * {@code java.lang.$Auxin.data} is a public, mutable, JVM-global static that any code
     * in the process can write. Overwriting it with an object whose {@code equals} does not fill
     * in {@code args[0]} leaves a String where the instrumented class expects a {@code boolean[]}.
     * Run this AFTER ax-agent's premain (agent order decides) to see what that does.
     */
    static void tamper() {
        try {
            Class<?> c = Class.forName("java.lang.$Auxin");
            Object before = c.getField("data").get(null);
            c.getField("data").set(null, new RivalData());
            System.out.println("[rival] TAMPERED java.lang.$Auxin.data: "
                    + (before == null ? "null" : before.getClass().getName())
                    + " -> " + RivalData.class.getName());
        } catch (Throwable t) {
            System.out.println("[rival] could not tamper: " + t);
        }
    }

    /** Deliberately not ax-agent's Data: a rival's object answers equals() its own way. */
    static final class RivalData {
        @Override public boolean equals(Object o) { return false; }
        @Override public int hashCode() { return 0; }
    }

    /**
     * class java.lang.$Auxin { public static Object data; } -- assembled by hand.
     * Constant pool: 1=Class(#2) 2=Utf8 this 3=Class(#4) 4=Utf8 java/lang/Object
     *                5=Utf8 "data" 6=Utf8 "Ljava/lang/Object;"
     */
    static byte[] bridgeClassFile() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bos);
        o.writeInt(0xCAFEBABE);
        o.writeShort(0);            // minor
        o.writeShort(52);           // major (Java 8)
        o.writeShort(7);            // constant_pool_count = entries + 1
        o.writeByte(7); o.writeShort(2);                 // #1 Class -> #2
        o.writeByte(1); o.writeUTF("java/lang/$Auxin");   // #2
        o.writeByte(7); o.writeShort(4);                 // #3 Class -> #4
        o.writeByte(1); o.writeUTF("java/lang/Object");  // #4
        o.writeByte(1); o.writeUTF("data");              // #5
        o.writeByte(1); o.writeUTF("Ljava/lang/Object;");// #6
        o.writeShort(0x0021);       // ACC_PUBLIC | ACC_SUPER
        o.writeShort(1);            // this_class
        o.writeShort(3);            // super_class
        o.writeShort(0);            // interfaces
        o.writeShort(1);            // fields
        o.writeShort(0x0009);       // ACC_PUBLIC | ACC_STATIC
        o.writeShort(5);            // name -> "data"
        o.writeShort(6);            // descriptor
        o.writeShort(0);            // field attributes
        o.writeShort(0);            // methods
        o.writeShort(0);            // class attributes
        o.flush();
        return bos.toByteArray();
    }

    private RivalAgent() { }
}
