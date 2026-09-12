package iso.osgi;

/**
 * An INTERFACE with a default method and a static method, loaded from inside the bundle.
 *
 * <p>This type is the measurement for F2. An interface cannot hold a mutable static field, so
 * while the bridge was field-based ({@code ax.bridge.shape=field}, still tested by the
 * {@code strict-fieldshape} scenario) this interface got <b>no coverage at all</b> in OSGi and
 * was counted as {@code interfaceNeedsField}. The self-BSM bridge needs no field -- a
 * {@code private static} interface method is legal from class file 53 and condy needs 55 -- so
 * the same interface is now probed in every Felix configuration. The scenarios differ by exactly
 * one thing, and the coverage difference is attributable.
 */
public interface Helper {

    default int twice(int n) {
        int acc = 0;
        for (int i = 0; i < 2; i++) acc += n;
        return acc;
    }

    static int thrice(int n) {
        int acc = 0;
        for (int i = 0; i < 3; i++) acc += n;
        return acc;
    }
}
