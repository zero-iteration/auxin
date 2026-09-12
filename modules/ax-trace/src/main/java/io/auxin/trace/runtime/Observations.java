package io.auxin.trace.runtime;

import io.auxin.trace.config.Projection;
import io.auxin.trace.util.TLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The structural dispatch. Given any object, produce an {@link Observation} that says something
 * useful about its <i>shape</i> and nothing about its content.
 *
 * <p>This runs only on a traced request, so it may allocate and may reflect. It runs on the
 * application thread, which is why it is bounded: the dispatch is a fixed chain of
 * {@code instanceof} tests with no recursion into element types, and projection is one
 * reflective call per whitelisted getter with the {@link Method} cached.
 *
 * <h3>Deliberately not done</h3>
 * <ul>
 *   <li><b>No element inspection.</b> A {@code List} contributes its size, never the first
 *       element's type, because the first element of a list of 126 fares is a fare.</li>
 *   <li><b>No {@code toString}.</b> Not once, not for diagnostics. A domain object's
 *       {@code toString} is the single most reliable way to print a customer's name into a log,
 *       and calling it also runs arbitrary application code from inside a probe.</li>
 *   <li><b>No field reflection.</b> Only methods a human named in the projection config, and
 *       only if they take no arguments.</li>
 * </ul>
 */
public final class Observations {

    private static volatile Projection projection = Projection.empty();

    /** {@code binaryClassName + "#" + getter -> Method}, or a NOT_FOUND sentinel. */
    private static final Map<String, Object> GETTERS = new ConcurrentHashMap<String, Object>();
    private static final Object NOT_FOUND = new Object();

    public static void install(Projection p) { projection = p == null ? Projection.empty() : p; }

    public static Projection projection() { return projection; }

    // ---------------- primitives: no dispatch, no allocation beyond the record ----------------

    static Observation ofInt(int siteId, String name, String typeDesc, long v) {
        if ("Z".equals(typeDesc)) {
            return new Observation(siteId, name, Observation.BOOL, v != 0 ? 1 : 0,
                    v != 0 ? "true" : "false", null);
        }
        if ("C".equals(typeDesc)) {
            // A char IS content. Its numeric value is not reported; only that it is a char.
            return new Observation(siteId, name, Observation.LEN, 1, null, null);
        }
        return num(siteId, name, v);
    }

    static Observation ofLong(int siteId, String name, long v) { return num(siteId, name, v); }

    static Observation ofDouble(int siteId, String name, double v) {
        if (Redaction.deniedShape((long) v)) {
            TraceHealth.OBS_REDACTED.incrementAndGet();
            return new Observation(siteId, name, Observation.REDACTED, 0, "valueShape", null);
        }
        return new Observation(siteId, name, Observation.NUM, 0, v, true, null, null);
    }

    private static Observation num(int siteId, String name, long v) {
        if (Redaction.deniedShape(v)) {
            TraceHealth.OBS_REDACTED.incrementAndGet();
            return new Observation(siteId, name, Observation.REDACTED, 0, "valueShape", null);
        }
        return new Observation(siteId, name, Observation.NUM, v, null, null);
    }

    // ---------------- references ----------------

    /** The whole structural dispatch, in the order that makes the most useful answer win. */
    static Observation ofRef(int siteId, String name, Object o) {
        try {
            if (o == null) return new Observation(siteId, name, Observation.NULL, 0, null, null);

            if (o instanceof Collection) {
                return sized(siteId, name, ((Collection<?>) o).size());
            }
            if (o instanceof Map) {
                return sized(siteId, name, ((Map<?, ?>) o).size());
            }
            if (o instanceof Object[]) {
                return sized(siteId, name, ((Object[]) o).length);
            }
            if (o.getClass().isArray()) {
                return sized(siteId, name, java.lang.reflect.Array.getLength(o));
            }
            if (o instanceof Enum) {
                // A closed set the developer wrote. This is the one String-valued capture that
                // is safe, and it is frequently the whole answer ("routing=DOMESTIC").
                return new Observation(siteId, name, Observation.ENUM, ((Enum<?>) o).ordinal(),
                        ((Enum<?>) o).name(), null);
            }
            if (o instanceof Boolean) {
                boolean b = ((Boolean) o).booleanValue();
                return new Observation(siteId, name, Observation.BOOL, b ? 1 : 0,
                        b ? "true" : "false", null);
            }
            if (o instanceof Double || o instanceof Float) {
                return ofDouble(siteId, name, ((Number) o).doubleValue());
            }
            if (o instanceof Number) {
                return num(siteId, name, ((Number) o).longValue());
            }
            if (o instanceof CharSequence) {
                // LENGTH ONLY. A String is where free text lives.
                return new Observation(siteId, name, Observation.LEN,
                        ((CharSequence) o).length(), null, null);
            }
            if (o instanceof Throwable) {
                return new Observation(siteId, name, Observation.CLASS, 0,
                        o.getClass().getName(), null);
            }
            return new Observation(siteId, name, Observation.CLASS, 0, o.getClass().getName(),
                    project(o));
        } catch (Throwable t) {
            // Anything an application's size() or a projection getter can throw. A broken
            // observation is not a broken request.
            return new Observation(siteId, name, Observation.ERROR, 0, t.getClass().getName(),
                    null);
        }
    }

    private static Observation sized(int siteId, String name, int size) {
        return new Observation(siteId, name, Observation.SIZE, size, null, null);
    }

    /**
     * Whitelisted domain projection. Exact runtime class match — no hierarchy walk, so a
     * subclass never silently inherits its parent's projection.
     */
    private static List<Observation> project(Object o) {
        Projection p = projection;
        if (p.isEmpty()) return null;
        String type = o.getClass().getName();
        List<String> getters = p.gettersFor(type);
        if (getters == null || getters.isEmpty()) return null;
        List<Observation> out = new ArrayList<Observation>(getters.size());
        // THE TRACER MUST NOT OBSERVE ITSELF. A projected getter on an in-scope class carries
        // trace probes, so invoking it would push a frame, observe its parameters, and possibly
        // project again. The smoke suite's first run recorded 1,328 common-pool frames, most of
        // them the tracer reflecting on its own reflection.
        TraceRuntime.suppressBegin();
        try {
            for (int i = 0; i < getters.size(); i++) {
                String g = getters.get(i);
                Object result;
                Method m = getter(o.getClass(), type, g);
                if (m == null) continue;
                try {
                    result = m.invoke(o);
                } catch (Throwable t) {
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    out.add(new Observation(-1, g, Observation.ERROR, 0,
                            cause.getClass().getName(), null));
                    continue;
                }
                // The result goes through the SAME dispatch as everything else: a projected
                // String contributes its length, never its content.
                out.add(ofRef(-1, g, result));
            }
        } finally {
            TraceRuntime.suppressEnd();
        }
        return out.isEmpty() ? null : out;
    }

    private static Method getter(Class<?> c, String type, String name) {
        String key = type + "#" + name;
        Object cached = GETTERS.get(key);
        if (cached == NOT_FOUND) return null;
        if (cached != null) return (Method) cached;
        try {
            Method m = c.getMethod(name);              // PUBLIC, no-arg, only
            if (m.getParameterTypes().length != 0) {
                GETTERS.put(key, NOT_FOUND);
                return null;
            }
            if (m.getReturnType() == Void.TYPE) {
                GETTERS.put(key, NOT_FOUND);
                return null;
            }
            // No setAccessible. A projection that needs it is asking to read a private member,
            // which is exactly the thing a whitelist is supposed to forbid.
            GETTERS.put(key, m);
            return m;
        } catch (Throwable t) {
            if (TraceHealth.warnOnce("projectionMissing:" + key)) {
                TLog.warn("projection " + key + "() is not a public no-arg method on " + type
                        + ": ignored for the life of this JVM");
            }
            GETTERS.put(key, NOT_FOUND);
            return null;
        }
    }

    private Observations() { throw new AssertionError(); }
}
