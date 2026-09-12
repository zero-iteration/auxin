package ax.bench.g3;

/**
 * Same shape as Subject, but the installer gives this one a condy declared directly as "[Z"
 * instead of Ljava/lang/Object; + CHECKCAST. If the JVM accepts it, every probe site is 3 bytes
 * cheaper - which matters against MaxTrivialSize=6 and MaxInlineSize=35, and against A11's
 * load-time verification budget. JaCoCo uses the Object+CHECKCAST form because of JDK-8216970.
 */
public class SubjectRaw {
    public int one() { return 1; }
    public int two() { return 2; }
}
