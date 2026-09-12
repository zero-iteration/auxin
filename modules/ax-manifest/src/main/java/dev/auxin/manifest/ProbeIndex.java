package dev.auxin.manifest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic probe-index rule. This is the single most load-bearing piece of logic in the
 * project.
 *
 * <p>WHY it cannot be "whatever order ASM visited the methods in": PLAN-v2 replaced visit-order
 * indexing precisely because it causes <b>silent misattribution</b> (A14). The agent allocates a
 * {@code boolean[]} per class and writes hit {@code idx}; the analysis reads slot {@code idx} back
 * out of the manifest. Nothing at runtime can detect a disagreement -- a shifted index does not
 * throw, it just reports the wrong method as dead. Visit order depends on the compiler, on
 * incremental-vs-clean builds, and on any bytecode processor in the pipeline, so it is not a
 * property we may depend on.
 *
 * <p>The rule, verbatim from CONTRACTS section 1:
 * <ol>
 *   <li>sort {@code (className, methodName, descriptor)} lexicographically;</li>
 *   <li>number from 0 <b>within each class</b>;</li>
 *   <li>skip synthetic and bridge methods, {@code <clinit>}, and abstract/native.</li>
 * </ol>
 *
 * <p>"Lexicographically" means {@link String#compareTo} -- UTF-16 code-unit order. A locale-aware
 * {@code Collator} would produce different indices in a different locale, which is the same defect
 * wearing a different hat.
 */
public final class ProbeIndex {

    /**
     * Sort key. The class name participates even though numbering restarts per class, because the
     * rule is specified over the whole triple and a stable global order makes the assignment
     * reproducible regardless of how the caller grouped its input.
     */
    private static final Comparator<MethodCandidate> LEXICOGRAPHIC = new Comparator<MethodCandidate>() {
        @Override
        public int compare(MethodCandidate a, MethodCandidate b) {
            int c = a.className().compareTo(b.className());
            if (c != 0) {
                return c;
            }
            c = a.methodName().compareTo(b.methodName());
            if (c != 0) {
                return c;
            }
            return a.descriptor().compareTo(b.descriptor());
        }
    };

    private ProbeIndex() {
    }

    /**
     * Assigns probe indices to every probeable candidate.
     *
     * <p>The result is a map from class name to that class's method entries, ordered by index. Both
     * the map's key order and each list's order are deterministic functions of the input
     * <em>set</em> -- the order in which candidates were supplied is irrelevant, which is what makes
     * the output reproducible across builds and machines.
     *
     * @throws IllegalArgumentException if two candidates share a {@code (class, name, descriptor)}
     *         triple. That cannot happen in a well-formed class file, and if it does happen the
     *         only safe response is to stop, because one of the two would silently take the other's
     *         probe slot.
     */
    public static Map<String, List<MethodEntry>> assign(Collection<MethodCandidate> candidates) {
        List<MethodCandidate> probeable = new ArrayList<MethodCandidate>(candidates.size());
        Set<String> seen = new HashSet<String>();
        for (MethodCandidate candidate : candidates) {
            if (!candidate.isProbeable()) {
                continue;
            }
            if (!seen.add(candidate.className() + '#' + candidate.nameAndDesc())) {
                throw new IllegalArgumentException("duplicate method candidate: " + candidate);
            }
            probeable.add(candidate);
        }
        Collections.sort(probeable, LEXICOGRAPHIC);

        Map<String, List<MethodEntry>> byClass = new LinkedHashMap<String, List<MethodEntry>>();
        String currentClass = null;
        int nextIdx = 0;
        List<MethodEntry> current = null;
        for (MethodCandidate candidate : probeable) {
            if (!candidate.className().equals(currentClass)) {
                currentClass = candidate.className();
                nextIdx = 0;
                current = new ArrayList<MethodEntry>();
                byClass.put(currentClass, current);
            }
            current.add(toEntry(candidate, nextIdx++));
        }
        for (Map.Entry<String, List<MethodEntry>> e : byClass.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(byClass);
    }

    private static MethodEntry toEntry(MethodCandidate candidate, int idx) {
        return MethodEntry.builder(idx, candidate.methodName(), candidate.descriptor())
                .line(candidate.line())
                .access(candidate.access())
                // Always false: synthetic candidates never reach here, and recording "false" is the
                // honest statement that this entry describes a real, author-written method.
                .synthetic(false)
                .tier2(candidate.tier2())
                .dynamicallyObservable(candidate.dynamicallyObservable())
                .shortCircuitable(candidate.shortCircuitable())
                .build();
    }
}
