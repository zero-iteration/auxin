package io.auxin.trace.config;

import java.util.ArrayList;
import java.util.List;

/**
 * DEFAULT DENY, same rule as {@code io.auxin.agent.config.Scope}: an unset include list
 * instruments nothing. Prefix matching on the internal name, never a parse.
 *
 * <p>The tracer keeps this invariant even though it gives up others, and for a sharper reason
 * than tier-1 has: a trace probe records <i>values</i>. A wide scope is not merely a cost here,
 * it is a data-exposure surface. There is no "instrument everything" switch in this module and
 * there is not going to be one.
 */
public final class TraceScope {

    private final String[] include;
    private final String[] exclude;

    public TraceScope(List<String> includePackages, List<String> excludePackages) {
        this.include = internal(includePackages);
        this.exclude = internal(excludePackages);
    }

    private static String[] internal(List<String> dotted) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < dotted.size(); i++) {
            String p = dotted.get(i).trim().replace('.', '/');
            if (p.length() == 0) continue;
            out.add(p);
        }
        return out.toArray(new String[0]);
    }

    public boolean empty() { return include.length == 0; }

    public boolean included(String internalName) {
        for (int i = 0; i < include.length; i++) {
            if (matches(internalName, include[i])) return true;
        }
        return false;
    }

    public boolean excluded(String internalName) {
        for (int i = 0; i < exclude.length; i++) {
            if (matches(internalName, exclude[i])) return true;
        }
        return false;
    }

    /**
     * The prefix that admitted this class, or null. Used only for diagnostics, so that
     * "nothing was traced" is answerable from the log.
     */
    public String matchedPrefix(String internalName) {
        String best = null;
        for (int i = 0; i < include.length; i++) {
            if (matches(internalName, include[i])
                    && (best == null || include[i].length() > best.length())) {
                best = include[i];
            }
        }
        return best;
    }

    /**
     * A package prefix match, on a SEGMENT boundary. {@code com/acme} must not match
     * {@code com/acmecorp/Foo}: prefix matching without the boundary check is how a scope
     * silently widens.
     */
    private static boolean matches(String internalName, String prefix) {
        if (!internalName.startsWith(prefix)) return false;
        if (internalName.length() == prefix.length()) return true;
        char next = internalName.charAt(prefix.length());
        return next == '/' || next == '$';
    }

    public String includeDescription() { return join(include); }

    public String excludeDescription() { return join(exclude); }

    /** The include list as dotted package names, for overlap analysis against ax-agent's scope. */
    public List<String> includeDotted() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < include.length; i++) out.add(include[i].replace('/', '.'));
        return out;
    }

    private static String join(String[] a) {
        if (a.length == 0) return "<none>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(a[i].replace('/', '.'));
        }
        return sb.toString();
    }
}
