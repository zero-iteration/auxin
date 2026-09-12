package dev.auxin.staticscan.scan;

import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;

import java.util.Set;

/**
 * Detects methods a framework proxy may return from without ever entering the body.
 *
 * <p>WHY this is the sharpest false-positive vector in the system: a {@code @Cacheable} method with
 * a warm or shared cache <b>never executes</b>, no matter how heavily the feature is used. The probe
 * correctly reports "never invoked". The conclusion "dead" would be confidently, expensively wrong.
 * The same holds for a circuit breaker in the open state, a retry that never re-enters, and -- more
 * rarely -- transaction advice.
 *
 * <p>WHY matching is on the annotation's <b>simple name</b> rather than its full descriptor: the
 * same annotation ships under {@code javax.} and {@code jakarta.}, under Spring Retry and
 * Resilience4j and Hystrix, and vendors re-declare their own. Simple-name matching over-matches --
 * someone's unrelated {@code @Cacheable} will be caught too -- and over-matching is the safe
 * direction here, because the only consequence downstream is that the method becomes UNKNOWN
 * instead of a deletion candidate.
 */
public final class ShortCircuitCatalog {

    private static final Set<String> SHORT_CIRCUITING_ANNOTATIONS = Set.of(
            // Caching: the target body is skipped entirely on a hit.
            "Cacheable",
            "CachePut",
            "CacheResult",
            "Caching",
            // Resilience: open breaker, exhausted bulkhead, rejected rate limiter, timed-out call.
            "CircuitBreaker",
            "Retryable",
            "Recover",
            "HystrixCommand",
            "Bulkhead",
            "RateLimiter",
            "TimeLimiter",
            // Rarer, but a transaction interceptor can still return before the target runs.
            "Transactional");

    /**
     * {@code true} if the method, or the class declaring it, carries a short-circuiting annotation.
     *
     * <p>Class level counts because Spring applies type-level {@code @Transactional} and
     * {@code @Cacheable} to every method of the bean.
     */
    public boolean isShortCircuitable(ClassModel owner, MethodModel method) {
        return containsShortCircuiting(method.annotationDescriptors())
                || containsShortCircuiting(owner.annotationDescriptors());
    }

    private boolean containsShortCircuiting(Set<String> annotationDescriptors) {
        for (String descriptor : annotationDescriptors) {
            if (SHORT_CIRCUITING_ANNOTATIONS.contains(simpleNameOf(descriptor))) {
                return true;
            }
        }
        return false;
    }

    /** {@code Lorg/springframework/cache/annotation/Cacheable;} -&gt; {@code Cacheable}. */
    private String simpleNameOf(String descriptor) {
        int end = descriptor.endsWith(";") ? descriptor.length() - 1 : descriptor.length();
        int start = Math.max(descriptor.lastIndexOf('/', end), descriptor.lastIndexOf('$', end)) + 1;
        if (start == 0) {
            start = descriptor.startsWith("L") ? 1 : 0;
        }
        return descriptor.substring(start, end);
    }
}
