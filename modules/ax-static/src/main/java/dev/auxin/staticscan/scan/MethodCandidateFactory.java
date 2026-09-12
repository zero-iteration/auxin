package dev.auxin.staticscan.scan;

import dev.auxin.manifest.MethodCandidate;
import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;

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
 * <p>Note on {@code tier2}: ax-static has no allowlist input, so every candidate is emitted with
 * {@code tier2=false}. The tier-2 boundary list is an operational decision (roughly 50-200 methods)
 * that does not belong in a build-time scan of arbitrary bytecode.
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
}
