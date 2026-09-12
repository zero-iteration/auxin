package io.auxin.agent.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Name-only, default-deny scope matching — the first-pass filter.
 *
 * <p>This is the single highest-leverage startup mitigation (VALIDATION A11/C13): reject on a
 * string prefix BEFORE any class file is parsed. The 30s -&gt; 10min regression in A11 came from
 * transformers that evaluated matchers requiring the class hierarchy.
 *
 * <p>Prefixes are stored in internal form ({@code com/acme/}) so no per-class {@code replace()}
 * is needed at transform time.
 */
public final class Scope {

    private final String[] include;
    private final String[] exclude;
    private final List<String> includeRaw;
    private final List<String> excludeRaw;

    public Scope(List<String> includePackages, List<String> excludePackages) {
        this.includeRaw = includePackages;
        this.excludeRaw = excludePackages;
        this.include = toPrefixes(includePackages);
        this.exclude = toPrefixes(excludePackages);
    }

    private static String[] toPrefixes(List<String> pkgs) {
        List<String> out = new ArrayList<String>(pkgs.size());
        for (int i = 0; i < pkgs.size(); i++) {
            String p = pkgs.get(i).replace('.', '/');
            while (p.endsWith("*")) p = p.substring(0, p.length() - 1);
            if (p.length() == 0) continue;
            out.add(p);
        }
        return out.toArray(new String[0]);
    }

    /** DEFAULT DENY: an empty include list matches nothing. */
    public boolean included(String internalName) {
        for (int i = 0; i < include.length; i++) {
            if (internalName.startsWith(include[i])) return true;
        }
        return false;
    }

    /**
     * The longest include prefix this name matches, or {@code null} when it matches none.
     *
     * <p>Used to settle precedence against the soft ignore prefixes (G5-BUG-1): an operator who
     * named something at least as specific as an ignore prefix meant it, and a name filter must
     * never quietly overrule an explicit scope. Only called on the rare veto path, never on the
     * per-class fast path — {@link #included(String)} stays a single prefix scan.
     */
    public String matchedPrefix(String internalName) {
        String best = null;
        for (int i = 0; i < include.length; i++) {
            if (internalName.startsWith(include[i])
                    && (best == null || include[i].length() > best.length())) {
                best = include[i];
            }
        }
        return best;
    }

    /** Package-level kill switch. */
    public boolean excluded(String internalName) {
        for (int i = 0; i < exclude.length; i++) {
            if (internalName.startsWith(exclude[i])) return true;
        }
        return false;
    }

    public boolean empty() { return include.length == 0; }

    public String includeDescription() { return includeRaw.isEmpty() ? "<none>" : includeRaw.toString(); }

    public String excludeDescription() { return excludeRaw.isEmpty() ? "<none>" : excludeRaw.toString(); }
}
