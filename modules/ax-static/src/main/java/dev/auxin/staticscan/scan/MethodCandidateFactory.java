package dev.auxin.staticscan.scan;

import dev.auxin.manifest.MethodCandidate;
import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;
import dev.auxin.staticscan.tier2.Tier2Selection;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns scanned methods into the ax-manifest {@link MethodCandidate} form that
 * {@link dev.auxin.manifest.ProbeIndex} consumes.
 *
 * <p>WHY a separate collaborator rather than a method on the scanner: this is the seam where
 * bytecode facts become contract facts, and it is where the two "runtime absence proves nothing"
 * classifications get attached. Keeping it apart means the C51 and proxy-short-circuit rules can be
 * tested without an ASM fixture, and swapped without touching the scanner.
 *
 * <p>Note on {@code tier2}: {@link #candidatesOf(ClassModel)} emits {@code tier2=false}, because the
 * allowlist is a property of the whole artifact -- it needs the entry-point list and the pattern
 * flags, neither of which exists while a single class is being read. The flag is applied afterwards
 * by {@link #withTier2(List, Tier2Selection)} from a
 * {@link dev.auxin.staticscan.tier2.Tier2Selector} decision. This class stays the only place that
 * constructs a {@code MethodCandidate}, so there is exactly one spelling of the contract mapping.
 */
public final class MethodCandidateFactory {

    private final DynamicObservability dynamicObservability;
    private final ShortCircuitCatalog shortCircuitCatalog;

    public MethodCandidateFactory(DynamicObservability dynamicObservability,
                                  ShortCircuitCatalog shortCircuitCatalog) {
        this.dynamicObservability = dynamicObservability;
        this.shortCircuitCatalog = shortCircuitCatalog;
    }

    /**
     * One candidate per declared method, including ones that will be skipped. The skip decision
     * belongs to {@link MethodCandidate#isProbeable()} so that ax-agent applies exactly the same
     * rule against the bytes it is about to transform.
     */
    public List<MethodCandidate> candidatesOf(ClassModel owner) {
        List<MethodCandidate> candidates = new ArrayList<>(owner.methods().size());
        for (MethodModel method : owner.methods()) {
            candidates.add(MethodCandidate.builder(owner.name(), method.name(), method.desc())
                    .line(method.line())
                    .access(method.accessString())
                    .synthetic(method.isSynthetic())
                    .bridge(method.isBridge())
                    .isAbstract(method.isAbstract())
                    .isNative(method.isNative())
                    .tier2(false)
                    .dynamicallyObservable(dynamicObservability.isObservable(method))
                    .shortCircuitable(shortCircuitCatalog.isShortCircuitable(owner, method))
                    .build());
        }
        return candidates;
    }

    /**
     * Returns {@code candidates} with {@code tier2=true} on the methods {@code selection} chose,
     * in the same order.
     *
     * <p>WHY a second pass rather than deciding during the first: the selector's own eligibility
     * rule reads {@code dynamicallyObservable} and {@code isProbeable} off the candidate, so the
     * candidates have to exist before the selection can be computed. Rebuilding is cheaper than
     * re-running the body-shape and annotation analyses a second time, and far cheaper than a
     * second read of the artifact.
     */
    public List<MethodCandidate> withTier2(List<MethodCandidate> candidates,
                                           Tier2Selection selection) {
        if (selection.isEmpty()) {
            return candidates;
        }
        List<MethodCandidate> updated = new ArrayList<>(candidates.size());
        for (MethodCandidate candidate : candidates) {
            boolean tier2 = selection.isTier2(
                    candidate.className(), candidate.methodName(), candidate.descriptor());
            updated.add(tier2 ? copyWithTier2(candidate) : candidate);
        }
        return updated;
    }

    private static MethodCandidate copyWithTier2(MethodCandidate candidate) {
        return MethodCandidate.builder(
                        candidate.className(), candidate.methodName(), candidate.descriptor())
                .line(candidate.line())
                .access(candidate.access())
                .synthetic(candidate.synthetic())
                .bridge(candidate.bridge())
                .isAbstract(candidate.isAbstract())
                .isNative(candidate.isNative())
                .tier2(true)
                .dynamicallyObservable(candidate.dynamicallyObservable())
                .shortCircuitable(candidate.shortCircuitable())
                .build();
    }
}
