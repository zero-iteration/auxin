package io.auxin.trace.config;

import io.auxin.trace.runtime.Redaction;
import io.auxin.trace.util.TLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Per-service enrichment: {@code type -> getters}. <b>Whitelist only.</b> Nothing here ever
 * reflects over an arbitrary field, walks an object graph, or calls a method that is not named
 * in this file by a human.
 *
 * <h3>Why properties and not YAML</h3>
 * The agent may add no dependency but shaded ASM (PLAN-v2 "Non-negotiables in code"), and the
 * JDK 8 baseline has no YAML parser. Writing one is a parser in an agent, on the class-load
 * path, for a file format with an indentation-sensitive grammar — not a trade worth making for
 * a two-level map. So the file is {@code java.util.Properties}, one line per type:
 *
 * <pre>
 *   # auxin-trace-projection.properties
 *   com.acme.search.FareResult   = getCarrierCode, getStops, isRefundable, getCabinClass
 *   com.acme.search.SearchRequest= getPassengerCount, getTripType
 * </pre>
 *
 * Onboarding a service is this file, not a code change, which was the requirement.
 *
 * <h3>The four rules that make a whitelist safe</h3>
 * <ol>
 *   <li><b>No-arg only.</b> A getter with parameters is refused. We are not choosing arguments
 *       on someone's behalf.</li>
 *   <li><b>Denylisted names are refused at load</b> ({@link Redaction#deniedName}), loudly, per
 *       entry. An operator who writes {@code getCustomerEmail} learns immediately.</li>
 *   <li><b>Structural capture of the result, never the result.</b> A projected getter goes
 *       through the same {@code Observations} dispatch as everything else: a String contributes
 *       its <i>length</i>, a collection its <i>size</i>, an enum its <i>name</i>, a number its
 *       value (subject to the value-shape check), an object its class name. There is no path on
 *       which a projected String's content reaches the wire.</li>
 *   <li><b>Exact type match on the runtime class</b>, not {@code isAssignableFrom}. A
 *       superclass entry does not silently apply to a subclass whose getter means something
 *       else, and there is no hierarchy walk on the capture path.</li>
 * </ol>
 */
public final class Projection {

    /** The conventional file name, looked for next to the agent jar and in the CWD. */
    public static final String DEFAULT_FILE = "auxin-trace-projection.properties";

    private static final Projection EMPTY =
            new Projection(Collections.<String, List<String>>emptyMap(), "<none>", 0, 0);

    private final Map<String, List<String>> byType;
    private final String source;
    private final int accepted;
    private final int refused;

    private Projection(Map<String, List<String>> byType, String source, int accepted, int refused) {
        this.byType = byType;
        this.source = source;
        this.accepted = accepted;
        this.refused = refused;
    }

    public static Projection empty() { return EMPTY; }

    public boolean isEmpty() { return byType.isEmpty(); }

    public int typeCount() { return byType.size(); }

    public int getterCount() { return accepted; }

    public int refusedCount() { return refused; }

    public String source() { return source; }

    /** Getters whitelisted for this exact binary class name, or null. Never allocates. */
    public List<String> gettersFor(String binaryClassName) {
        return byType.get(binaryClassName);
    }

    /**
     * Loads the projection config. Never throws: a broken or missing file means no enrichment,
     * which is a worse trace, not an outage.
     *
     * @param path an explicit path, or empty to look for {@link #DEFAULT_FILE} in the CWD.
     */
    public static Projection load(String path) {
        File f;
        if (path != null && path.length() > 0) {
            f = new File(path);
            if (!f.isFile()) {
                TLog.warn("ax.trace.projection=" + path + " does not exist: no per-service "
                        + "enrichment. Structural capture (sizes, null-ness, enum names, "
                        + "primitives, exception class names) is unaffected.");
                return EMPTY;
            }
        } else {
            f = new File(DEFAULT_FILE);
            if (!f.isFile()) return EMPTY;
        }
        try {
            Properties p = new Properties();
            InputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
            Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
            int ok = 0;
            int no = 0;
            for (String type : p.stringPropertyNames()) {
                String raw = p.getProperty(type);
                if (raw == null) continue;
                List<String> getters = new ArrayList<String>();
                for (String g : raw.split("[,:;\\s]+")) {
                    String name = g.trim();
                    if (name.length() == 0) continue;
                    if (name.indexOf('(') >= 0) {
                        refuse(type, name, "a projection names a method, never a signature");
                        no++;
                        continue;
                    }
                    if (Redaction.deniedName(name)) {
                        refuse(type, name, "the redaction denylist refuses this member name");
                        no++;
                        continue;
                    }
                    getters.add(name);
                    ok++;
                }
                if (!getters.isEmpty()) out.put(type.trim(), Collections.unmodifiableList(getters));
            }
            return new Projection(Collections.unmodifiableMap(out), f.getPath(), ok, no);
        } catch (Throwable t) {
            TLog.warn("could not read the projection config " + f.getPath()
                    + ": no per-service enrichment (" + t + ")", t);
            return EMPTY;
        }
    }

    private static void refuse(String type, String getter, String why) {
        TLog.warn("projection REFUSED " + type + "#" + getter + "(): " + why
                + ". Nothing from this getter will ever be recorded. Add its normalised name to "
                + "ax.trace.redact.allow if this is a false positive.");
    }

    public String summary() {
        if (isEmpty()) return "<none>";
        return source + " types=" + typeCount() + " getters=" + accepted
                + (refused > 0 ? " REFUSED=" + refused : "");
    }
}
