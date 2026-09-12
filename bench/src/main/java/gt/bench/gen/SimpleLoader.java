package ax.bench.gen;

import java.util.Map;

/**
 * Defines a fixed set of classes from byte arrays.
 *
 * When {@code childFirst} is set it shadows classes that also exist in the parent - that is how
 * G2 runs the SAME chain classes instrumented and uninstrumented with identical names, so the
 * -XX:+PrintInlining output of the two runs can be diffed line for line.
 */
public final class SimpleLoader extends ClassLoader {

    private final Map<String, byte[]> defs;
    private final boolean childFirst;

    static { registerAsParallelCapable(); }

    public SimpleLoader(ClassLoader parent, Map<String, byte[]> defs, boolean childFirst) {
        super(parent);
        this.defs = defs;
        this.childFirst = childFirst;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null && childFirst && defs.containsKey(name)) c = findClass(name);
            if (c == null) c = super.loadClass(name, false);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = defs.get(name);
        if (b == null) throw new ClassNotFoundException(name);
        return defineClass(name, b, 0, b.length);
    }
}
