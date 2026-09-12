package dev.auxin.staticscan.api;

import dev.auxin.staticscan.model.ClassModel;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a class is API surface that consumers outside this artifact might call.
 *
 * <p>WHY the bias is towards {@code true}: PLAN-v2's correctness rule refuses a DEAD_CANDIDATE
 * verdict for anything on the public API surface, because downstream consumers of a library are
 * invisible to us -- no amount of runtime observation inside our own JVM can see them. Marking a
 * class API when it is not costs a verdict; marking it internal when it is not costs a caller.
 *
 * <p>The rule is deliberately small:
 * <ol>
 *   <li>a package segment of {@code internal} or {@code impl} is an explicit statement by the
 *       author that this is not for outside use -- believe it;</li>
 *   <li>otherwise the class is API surface exactly when it is {@code public}.</li>
 * </ol>
 *
 * <p>The "when unsure, mark true" instruction is satisfied by the class-file format itself rather
 * than by an extra branch: a {@code protected} nested class is emitted with {@code ACC_PUBLIC}
 * because {@code protected} is not representable in a class's access flags, so ambiguous nested
 * types already read as public here. That is the direction we want to be wrong in.
 */
public final class PublicApiDetector {

    /** Package segments that conventionally mean "not for outside use". */
    private static final Set<String> INTERNAL_SEGMENTS = Set.of("internal", "impl");

    public boolean isPublicApi(ClassModel cls) {
        if (hasInternalSegment(cls.packageName())) {
            return false;
        }
        return cls.isPublic();
    }

    private boolean hasInternalSegment(String packageName) {
        if (packageName.isEmpty()) {
            return false;
        }
        for (String segment : packageName.split("\\.")) {
            if (INTERNAL_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
