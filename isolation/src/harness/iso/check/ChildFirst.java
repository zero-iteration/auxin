package iso.check;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A genuinely child-first (parent-last) class loader -- the shape Tomcat's WebappClassLoader and
 * Spring Boot's LaunchedClassLoader use.
 *
 * <p>Order: already-loaded, then the {@code java.} / {@code jdk.} / {@code sun.} prefixes to
 * the parent (mandatory: the JVM forbids a user loader from defining a class in java.), then OUR
 * urls, and only then the parent. So anything present in {@code urls} SHADOWS the application
 * class path, including the agent jar.
 */
public final class ChildFirst extends URLClassLoader {

    static { ClassLoader.registerAsParallelCapable(); }

    public ChildFirst(URL[] urls, ClassLoader parent) {
        super("child-first", urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                        || name.startsWith("javax.")) {
                    c = super.loadClass(name, false);          // parent-first, always
                } else {
                    try {
                        c = findClass(name);                    // CHILD FIRST
                    } catch (ClassNotFoundException notOurs) {
                        c = super.loadClass(name, false);       // parent last
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }
}
