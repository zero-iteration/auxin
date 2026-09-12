package dev.auxin.staticscan.tier2;

import dev.auxin.staticscan.model.MethodRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of tier-2 selection: which methods get timed, and the one-line reason each.
 *
 * <p>WHY the reason is carried rather than just the boolean: tier-2 is the only tier that costs the
 * application measurable time on every call, so "why is this method being timed?" is a question an
 * operator will ask months later, in front of a latency graph, with nobody around who remembers the
 * flags. A bare {@code tier2: true} cannot answer it; {@code tier2Reason: "pattern:com.acme.**#*"}
 * can, and points straight at the flag to change.
 *
 * <p>Iteration order is sorted by {@code (class, method, descriptor)} so the CLI report and the
 * manifest are byte-identical across runs of the same artifact.
 */
public final class Tier2Selection {

    /** Width the reason column is padded to in {@link #breakdownLines()}. */
    private static final int REASON_COLUMN = 44;

    private static final Tier2Selection EMPTY =
            new Tier2Selection(Collections.emptyMap(), List.of(), List.of());

    private final Map<MethodRef, String> reasons;
    private final List<String> unmatchedIncludes;
    private final List<String> unmatchedExcludes;

    Tier2Selection(Map<MethodRef, String> reasons,
                   List<String> unmatchedIncludes,
                   List<String> unmatchedExcludes) {
        this.reasons = Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
        this.unmatchedIncludes = List.copyOf(unmatchedIncludes);
        this.unmatchedExcludes = List.copyOf(unmatchedExcludes);
    }

    /** A selection of nothing: no method is tier-2 and no pattern was supplied. */
    public static Tier2Selection empty() {
        return EMPTY;
    }

    /** How many methods will carry tier-2 instrumentation. This is what the budget limits. */
    public int count() {
        return reasons.size();
    }

    public boolean isEmpty() {
        return reasons.isEmpty();
    }

    /** Whether this method is on the tier-2 allowlist. */
    public boolean isTier2(String className, String methodName, String desc) {
        return reasons.containsKey(new MethodRef(className, methodName, desc));
    }

    /**
     * The reason this method is tier-2, or {@code null} if it is not.
     *
     * <p>{@code null} is the honest value and the manifest renders it as an absent field: no reason
     * means not tier-2, never "tier-2 for reasons unknown".
     */
    public String reasonFor(String className, String methodName, String desc) {
        return reasons.get(new MethodRef(className, methodName, desc));
    }

    /** Every selected method with its reason, in sorted order. Immutable. */
    public Map<MethodRef, String> reasons() {
        return reasons;
    }

    /**
     * Count of selected methods per reason, ordered by count descending then reason ascending --
     * the order an operator wants when the question is "what is spending my budget?".
     */
    public Map<String, Integer> breakdown() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String reason : reasons.values()) {
            counts.merge(reason, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : ordered) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(sorted);
    }

    /**
     * The {@link #breakdown()} as aligned {@code "<reason>  <count>"} lines.
     *
     * <p>Lives here rather than in each caller so the CLI report and the budget-overrun message
     * cannot drift into two different renderings of the same fact.
     */
    public List<String> breakdownLines() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : breakdown().entrySet()) {
            StringBuilder line = new StringBuilder(entry.getKey());
            while (line.length() < REASON_COLUMN) {
                line.append(' ');
            }
            lines.add(line.append(' ').append(entry.getValue()).toString());
        }
        return lines;
    }

    /**
     * {@code --tier2} patterns that matched no method in this artifact.
     *
     * <p>Reported rather than ignored because a typo in a pattern produces exactly the same manifest
     * as not passing it: no timings, no error. That is the silent failure this project keeps
     * refusing to ship.
     */
    public List<String> unmatchedIncludes() {
        return unmatchedIncludes;
    }

    /** {@code --tier2-exclude} patterns that excluded nothing. Same reasoning. */
    public List<String> unmatchedExcludes() {
        return unmatchedExcludes;
    }
}
