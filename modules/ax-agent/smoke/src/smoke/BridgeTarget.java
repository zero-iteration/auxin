package smoke;

/**
 * Loaded by a class loader whose parent is the bootstrap loader, so it can see {@code java.*}
 * and nothing else — OSGi, JBoss Modules, a JPMS named module and some fat jars in miniature.
 *
 * <p>An agent-jar reference in this class's constant pool would fail to resolve at runtime, so
 * it must be instrumented through {@code java.lang.$Auxin} or not at all. Deliberately
 * self-contained: it references no type outside {@code java.lang}.
 */
public class BridgeTarget {

    public int work(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += i;
        return s;
    }

    public int idle(int n) {
        int s = 1;
        for (int i = 0; i < n; i++) s *= 2;
        return s;
    }
}
