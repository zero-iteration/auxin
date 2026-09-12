package io.auxin.trace.util;

/**
 * stderr logging, no dependencies, never throws. {@code io.auxin.agent.util.Log}'s shape with a
 * distinct prefix ({@code [auxin/trace]}) so a reader can tell which TIER a line came from.
 *
 * <p>It said {@code [ax-trace]} while the tracer was a separate {@code -javaagent}, which now
 * reads as a second agent that no longer exists. The level still defaults to the agent's own
 * ({@code ax.log.level}) unless {@code ax.trace.log.level} overrides it, so one switch quiets
 * both unless an operator asks for otherwise.
 */
public final class TLog {

    public static final int SILENT = 0;
    public static final int WARN = 1;
    public static final int INFO = 2;
    public static final int DEBUG = 3;

    private static volatile int level = INFO;

    public static void setLevel(int l) { level = l; }

    public static int parseLevel(String s, int def) {
        if (s == null) return def;
        String v = s.trim().toLowerCase();
        if (v.equals("silent") || v.equals("off") || v.equals("none")) return SILENT;
        if (v.equals("warn") || v.equals("warning") || v.equals("error")) return WARN;
        if (v.equals("info")) return INFO;
        if (v.equals("debug") || v.equals("trace")) return DEBUG;
        return def;
    }

    public static boolean debugEnabled() { return level >= DEBUG; }

    public static void info(String msg) { if (level >= INFO) out("INFO ", msg, null); }

    public static void warn(String msg) { if (level >= WARN) out("WARN ", msg, null); }

    public static void warn(String msg, Throwable t) { if (level >= WARN) out("WARN ", msg, t); }

    public static void debug(String msg) { if (level >= DEBUG) out("DEBUG", msg, null); }

    public static void debug(String msg, Throwable t) { if (level >= DEBUG) out("DEBUG", msg, t); }

    private static void out(String lvl, String msg, Throwable t) {
        try {
            System.err.println("[auxin/trace] " + lvl + " " + msg);
            if (t != null && level >= DEBUG) t.printStackTrace();
        } catch (Throwable ignored) {
            // logging must never take the application down
        }
    }

    private TLog() { throw new AssertionError(); }
}
