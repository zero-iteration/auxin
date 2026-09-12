package g5obs;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * G5 ordering observer — a fourth, deliberately PASSIVE java agent.
 *
 * <p>It registers two {@link ClassFileTransformer}s and modifies nothing (both always return
 * {@code null}). Its only job is to record, for every watched class, <b>which bytes each
 * transformer was handed</b>:
 *
 * <pre>
 *   OBS-INCAPABLE   registered with addTransformer(t)        -> retransform-INCAPABLE group
 *   OBS-CAPABLE     registered with addTransformer(t, true)  -> retransform-CAPABLE group
 * </pre>
 *
 * <p>The {@code ClassFileTransformer} javadoc says the incapable group runs first and the byte
 * arrays chain. So if this agent is listed <b>first</b> on the command line, OBS-INCAPABLE is the
 * first transformer registered anywhere and sees original bytes — while OBS-CAPABLE, registered
 * at the very same instant, still sees the output of JaCoCo and ax-agent, which registered
 * <i>later</i>. That single comparison is the empirical proof that capability dominates
 * {@code -javaagent} order. Listed <b>last</b>, OBS-CAPABLE additionally sees OTel's output.
 *
 * <p>Detection is by raw constant-pool marker, which needs no bytecode library:
 * {@code $axProbes} (ax-agent), {@code $jacocoData}/{@code $jacocoInit} (JaCoCo),
 * {@code io/opentelemetry} (OTel advice).
 *
 * <p>It also exposes {@link #retransform(Class)}: a genuinely third-party
 * {@code Instrumentation.retransformClasses} call, used to reproduce the Tier-1b interaction
 * where the JVM replays ax-agent's cached installer output.
 */
public final class OrderObserver {

    private static volatile Instrumentation inst;
    private static volatile String phase = "load";
    private static final List<String> EVENTS =
            Collections.synchronizedList(new ArrayList<String>());

    /** Class name prefixes we record. Everything else is ignored with a name-only check. */
    private static final String[] WATCH = {
            "io/auxin/demo/",
            "sun/net/www/protocol/http/HttpURLConnection",
            "sun/net/httpserver/",
            "com/sun/net/httpserver/",
    };

    /** Optional: -Dg5.dump.dir=<dir> writes every watched byte array this agent is handed. */
    private static final String DUMP_DIR = System.getProperty("g5.dump.dir", "");
    private static final String DUMP_CLASS =
            System.getProperty("g5.dump.class", "io/auxin/demo/model/Customer");
    private static final java.util.concurrent.atomic.AtomicInteger DUMP_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void premain(String args, Instrumentation i) {
        inst = i;
        i.addTransformer(new Obs("OBS-INCAPABLE"));          // 1-arg: canRetransform = false
        i.addTransformer(new Obs("OBS-CAPABLE"), true);      // 2-arg: canRetransform = true
        System.out.println("[g5obs] registered OBS-INCAPABLE + OBS-CAPABLE"
                + " retransformSupported=" + i.isRetransformClassesSupported());
    }

    public static void agentmain(String args, Instrumentation i) {
        premain(args, i);
    }

    // ---------------- hooks used by the harness ----------------

    public static void setPhase(String p) {
        phase = p;
    }

    /** A third party's retransform: NOT ax-agent's, NOT armed on ax-agent's stripper. */
    public static boolean retransform(Class<?> c) {
        Instrumentation i = inst;
        if (i == null || c == null) return false;
        try {
            i.retransformClasses(new Class<?>[]{c});
            return true;
        } catch (Throwable t) {
            EVENTS.add(phase + "\tRETRANSFORM-FAILED\t" + c.getName() + "\t" + t);
            System.out.println("[g5obs] retransform failed for " + c.getName() + ": " + t);
            return false;
        }
    }

    public static boolean available() {
        return inst != null;
    }

    public static List<String> events() {
        synchronized (EVENTS) {
            return new ArrayList<String>(EVENTS);
        }
    }

    public static void write(String path) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            try {
                w.write("# phase\ttransformer\tclass\tbeingRedefined\tbytes\tgt\tjacoco\totel\n");
                for (String e : events()) {
                    w.write(e);
                    w.write('\n');
                }
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            System.out.println("[g5obs] could not write events: " + t);
        }
    }

    // ---------------- the transformers ----------------

    private static final class Obs implements ClassFileTransformer {
        private final String label;

        Obs(String label) {
            this.label = label;
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                ProtectionDomain pd, byte[] buf) {
            try {
                if (name == null || buf == null || !watched(name)) return null;
                EVENTS.add(phase + "\t" + label + "\t" + name
                        + "\t" + (beingRedefined != null)
                        + "\t" + buf.length
                        + "\t" + (contains(buf, "$axProbes") ? 1 : 0)
                        + "\t" + ((contains(buf, "$jacocoData") || contains(buf, "$jacocoInit")) ? 1 : 0)
                        + "\t" + (contains(buf, "io/opentelemetry") ? 1 : 0));
                dump(name, buf);
            } catch (Throwable ignored) {
                // an observer must never be the thing that breaks the JVM
            }
            return null;   // NEVER modify. This agent exists only to watch.
        }
    }

    /**
     * Writes the exact bytes this transformer was handed. Used to prove, with javap, whether a
     * retransform actually changed anything -- {@code retransformClasses} returning normally
     * says nothing about whether any transformer modified the class.
     */
    private static void dump(String internalName, byte[] buf) {
        if (DUMP_DIR.isEmpty() || !internalName.equals(DUMP_CLASS)) return;
        try {
            java.io.File dir = new java.io.File(DUMP_DIR);
            dir.mkdirs();
            String fn = String.format("%03d", Integer.valueOf(DUMP_SEQ.incrementAndGet()))
                    + "." + phase + "." + internalName.replace('/', '.') + ".class";
            java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, fn));
            try {
                o.write(buf);
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean watched(String internalName) {
        for (int i = 0; i < WATCH.length; i++) {
            if (internalName.startsWith(WATCH[i])) return true;
        }
        return false;
    }

    /** Naive substring search over the raw class file — enough for constant-pool markers. */
    static boolean contains(byte[] hay, String needleStr) {
        byte[] needle = new byte[needleStr.length()];
        for (int i = 0; i < needleStr.length(); i++) needle[i] = (byte) needleStr.charAt(i);
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private OrderObserver() {
        throw new AssertionError();
    }
}
