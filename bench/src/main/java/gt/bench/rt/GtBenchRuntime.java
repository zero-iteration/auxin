package ax.bench.rt;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Condy bootstrap target + probe storage for the benchmark harness.
 *
 * Mirrors the proven E2 reference (scratchpad/condy/src/GtRuntime.java): the BSM signature is
 * {@code (Lookup,String,Class,String,int)[Z} and the condy is DECLARED as
 * {@code Ljava/lang/Object;} at the use site, followed by {@code CHECKCAST [Z}
 * (JaCoCo's JDK-8216970 workaround). G3 additionally probes whether the raw {@code [Z}
 * descriptor works on this JDK, since it would save 3 bytes per probe site.
 */
public final class GtBenchRuntime {

    public static final Map<String, boolean[]> DATA = new ConcurrentHashMap<>();

    private GtBenchRuntime() {}

    /** BSM used by every arm. Returns the per-class probe array. */
    public static boolean[] bootstrap(MethodHandles.Lookup lookup, String name, Class<?> type,
                                      String owner, int count) {
        return DATA.computeIfAbsent(owner, k -> new boolean[count]);
    }

    public static boolean[] probes(String owner) {
        return DATA.get(owner);
    }

    public static void reset() {
        DATA.clear();
    }

    /** Compact "101" rendering used by the G3 assertions. */
    public static String render(String owner) {
        boolean[] p = DATA.get(owner);
        if (p == null) return "NONE";
        StringBuilder sb = new StringBuilder(p.length);
        for (boolean b : p) sb.append(b ? '1' : '0');
        return sb.toString();
    }

    public static int countSet(String owner) {
        boolean[] p = DATA.get(owner);
        if (p == null) return -1;
        int n = 0;
        for (boolean b : p) if (b) n++;
        return n;
    }
}
