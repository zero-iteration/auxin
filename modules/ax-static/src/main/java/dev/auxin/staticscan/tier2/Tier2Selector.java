package dev.auxin.staticscan.tier2;

import dev.auxin.manifest.EntryPoint;
import dev.auxin.manifest.MethodCandidate;
import dev.auxin.staticscan.entry.EntryPointCatalog;
import dev.auxin.staticscan.model.MethodRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides which methods carry tier-2 rate/error/latency instrumentation, and why.
 *
 * <p>Two selection paths, in this order:
 * <ol>
 *   <li><b>Every entry point, automatically.</b> Under SCOPE-v3 auxin is the only agent in the
 *       runtime path, so this scan is the <em>only</em> source of TPS, error rate and p90 that will
 *       ever exist for the artifact. A boundary method with no timing is a boundary nobody can see.
 *       Entry points are exactly the boundary -- a controller, a listener, a scheduled job and a
 *       {@code main} have no caller inside the artifact -- so they are the correct default, and
 *       instrumenting a service needs no configuration at all to get the timings that matter.</li>
 *   <li><b>{@code --tier2} patterns, explicitly.</b> For the internals an operator has a reason to
 *       time: a repository, a pricing rule, one hot path.</li>
 * </ol>
 * {@code --tier2-exclude} is applied after both, so it can also drop an auto-selected entry point.
 * That is deliberate: a noisy health-check endpoint is the obvious first thing to stop timing, and
 * without an override the automatic half would be un-opt-out-able.
 *
 * <h2>What can never be tier-2, and why each is not the same rule</h2>
 * <ul>
 *   <li><b>Not probeable</b> -- synthetic, bridge, {@code <clinit>}, abstract, native. These carry
 *       no probe at all (CONTRACTS section 1) and have no author-written body to time.</li>
 *   <li><b>{@code dynamicallyObservable=false}</b> (C51) -- a single-instruction body or constant
 *       accessor "cannot be covered dynamically". Timing a method whose execution is not even
 *       distinguishable at runtime is pure cost for no signal.</li>
 *   <li><b>Test classes</b> (C50) -- a test's throughput is not the application's throughput, and
 *       test code must never be instrumented as if it were production traffic.</li>
 * </ul>
 * <b>{@code shortCircuitable=true} is deliberately not on that list.</b> A {@code @Cacheable} method
 * is barred from a <em>dead-code</em> verdict because a proxy may answer before its body runs; that
 * says nothing against timing it. The calls that do reach the body are real calls with real latency,
 * and the gap between invocation rate and body rate is itself the cache hit rate. Conflating the two
 * rules would silently drop exactly the methods whose timing is most interesting.
 */
public final class Tier2Selector {

    /** Reason prefix for the automatic path. The suffix is the entry-point kind. */
    public static final String ENTRY_POINT_REASON_PREFIX = "entryPoint:";

    /** Reason prefix for the explicit path. The suffix is the pattern, verbatim. */
    public static final String PATTERN_REASON_PREFIX = "pattern:";

    /**
     * Default budget. PLAN-v2 sizes the tier-2 allowlist at "roughly 50-200 boundary methods";
     * 250 leaves headroom above that band for an application with an unusually wide HTTP surface
     * without becoming a number that could be mistaken for "instrument everything".
     */
    public static final int DEFAULT_MAX = 250;

    private static final Comparator<MethodRef> REF_ORDER =
            Comparator.comparing(MethodRef::className)
                    .thenComparing(MethodRef::name)
                    .thenComparing(MethodRef::desc);

    private final EntryPointCatalog catalog;
    private final List<MethodPattern> includes;
    private final List<MethodPattern> excludes;
    private final int max;

    public Tier2Selector(EntryPointCatalog catalog,
                         List<MethodPattern> includes,
                         List<MethodPattern> excludes,
                         int max) {
        if (max < 0) {
            throw new IllegalArgumentException("tier-2 budget must not be negative, got " + max);
        }
        this.catalog = catalog;
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.max = max;
    }

    /** Compiles raw patterns; see {@link MethodPattern#compile(String)} for the syntax rules. */
    public static Tier2Selector of(Collection<String> includes, Collection<String> excludes, int max) {
        return new Tier2Selector(new EntryPointCatalog(), compileAll(includes), compileAll(excludes),
                max);
    }

    /** Entry points only, at the default budget: the zero-configuration behaviour. */
    public static Tier2Selector automatic() {
        return new Tier2Selector(new EntryPointCatalog(), List.of(), List.of(), DEFAULT_MAX);
    }

    /**
     * @param candidates     every scanned method, before probe indices are assigned
     * @param entryPoints    the detected entry points, including ones on test classes
     * @param testClassNames classes {@code TestClassifier} identified as test code
     * @throws Tier2BudgetExceededException if more methods are selected than the budget allows
     */
    public Tier2Selection select(Collection<MethodCandidate> candidates,
                                 Collection<EntryPoint> entryPoints,
                                 Set<String> testClassNames) {
        List<MethodCandidate> eligible = eligible(candidates, testClassNames);
        Set<MethodRef> eligibleRefs = new LinkedHashSet<>(eligible.size());
        for (MethodCandidate candidate : eligible) {
            eligibleRefs.add(refOf(candidate));
        }

        Map<MethodRef, String> reasons = new TreeMap<>(REF_ORDER);
        selectEntryPoints(entryPoints, testClassNames, eligibleRefs, reasons);

        Set<String> matchedIncludes = new LinkedHashSet<>();
        selectPatterns(eligible, reasons, matchedIncludes);

        Set<String> matchedExcludes = new LinkedHashSet<>();
        applyExcludes(reasons, matchedExcludes);

        Tier2Selection selection = new Tier2Selection(new LinkedHashMap<>(reasons),
                unmatched(includes, matchedIncludes), unmatched(excludes, matchedExcludes));
        if (selection.count() > max) {
            throw new Tier2BudgetExceededException(selection, max);
        }
        return selection;
    }

    /**
     * The automatic half. One method can produce several entry points -- a {@code @Scheduled} method
     * inside a {@code @RequestMapping} class yields both kinds -- and only one reason is reported,
     * so the choice is made by rule and not by annotation visit order: a kind written on the method
     * beats a kind propagated from the type, and otherwise the lexicographically first wins. The
     * reason is therefore a stable function of the artifact, and it prefers the answer that is more
     * true about the method.
     */
    private void selectEntryPoints(Collection<EntryPoint> entryPoints,
                                   Set<String> testClassNames,
                                   Set<MethodRef> eligibleRefs,
                                   Map<MethodRef, String> reasons) {
        for (EntryPoint entryPoint : entryPoints) {
            if (testClassNames.contains(entryPoint.className())) {
                continue;
            }
            MethodRef ref =
                    new MethodRef(entryPoint.className(), entryPoint.method(), entryPoint.desc());
            // A ServiceLoader entry point is declared for a class that need not be in this
            // artifact, and an entry point on an abstract or trivial method is not eligible.
            if (!eligibleRefs.contains(ref)) {
                continue;
            }
            String existing = reasons.get(ref);
            // Entry points are selected before any pattern, so an existing reason is always an
            // entry-point reason. The check is written so that reordering the stages would degrade
            // to "first kind wins" rather than to a StringIndexOutOfBoundsException.
            if (existing == null
                    || (existing.startsWith(ENTRY_POINT_REASON_PREFIX)
                            && preferredKind(entryPoint.kind(),
                                    existing.substring(ENTRY_POINT_REASON_PREFIX.length())))) {
                reasons.put(ref, ENTRY_POINT_REASON_PREFIX + entryPoint.kind());
            }
        }
    }

    /**
     * The explicit half. A method already selected as an entry point keeps that reason: the
     * structural fact is the more useful answer to "why is this timed?", and a pattern that also
     * happens to cover a controller has not added anything.
     */
    private void selectPatterns(List<MethodCandidate> eligible,
                                Map<MethodRef, String> reasons,
                                Set<String> matchedIncludes) {
        if (includes.isEmpty()) {
            return;
        }
        for (MethodCandidate candidate : eligible) {
            for (MethodPattern pattern : includes) {
                if (!pattern.matches(candidate.className(), candidate.methodName())) {
                    continue;
                }
                matchedIncludes.add(pattern.pattern());
                reasons.putIfAbsent(refOf(candidate), PATTERN_REASON_PREFIX + pattern.pattern());
            }
        }
    }

    /** Whether {@code candidate} is the better kind to report than {@code incumbent}. */
    private boolean preferredKind(String candidate, String incumbent) {
        boolean candidatePropagated = catalog.isPropagatedFromType(candidate);
        boolean incumbentPropagated = catalog.isPropagatedFromType(incumbent);
        if (candidatePropagated != incumbentPropagated) {
            return !candidatePropagated;
        }
        return candidate.compareTo(incumbent) < 0;
    }

    private void applyExcludes(Map<MethodRef, String> reasons, Set<String> matchedExcludes) {
        if (excludes.isEmpty()) {
            return;
        }
        List<MethodRef> selected = new ArrayList<>(reasons.keySet());
        for (MethodRef ref : selected) {
            for (MethodPattern pattern : excludes) {
                if (pattern.matches(ref.className(), ref.name())) {
                    matchedExcludes.add(pattern.pattern());
                    reasons.remove(ref);
                    break;
                }
            }
        }
    }

    private List<MethodCandidate> eligible(Collection<MethodCandidate> candidates,
                                           Set<String> testClassNames) {
        List<MethodCandidate> eligible = new ArrayList<>();
        for (MethodCandidate candidate : candidates) {
            if (!candidate.isProbeable()) {
                continue;
            }
            if (!candidate.dynamicallyObservable()) {
                continue;
            }
            if (testClassNames.contains(candidate.className())) {
                continue;
            }
            eligible.add(candidate);
        }
        return eligible;
    }

    private static List<String> unmatched(List<MethodPattern> patterns, Set<String> matched) {
        List<String> unmatched = new ArrayList<>();
        for (MethodPattern pattern : patterns) {
            if (!matched.contains(pattern.pattern())) {
                unmatched.add(pattern.pattern());
            }
        }
        return unmatched;
    }

    private static List<MethodPattern> compileAll(Collection<String> patterns) {
        List<MethodPattern> compiled = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            compiled.add(MethodPattern.compile(pattern));
        }
        return compiled;
    }

    private static MethodRef refOf(MethodCandidate candidate) {
        return new MethodRef(candidate.className(), candidate.methodName(), candidate.descriptor());
    }
}
