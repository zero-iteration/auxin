package dev.auxin.staticscan.linkage;

import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodModel;

import java.util.Set;

/**
 * Identifies test code (C50).
 *
 * <p>WHY this matters far more than it looks: Google's Sensenmann states it outright -- <i>"we cannot
 * use test runs as a 'liveness' signal... We would only be able to clean up untested code."</i> If
 * the agent is ever attached in CI, staging, or a canary smoke test, every tested method becomes
 * permanently "alive" and the system inverts into a tool that deletes only the untested parts of the
 * codebase. Marking test classes is the build-time half of preventing that.
 *
 * <p>Three independent signals, because none is sufficient alone:
 * <ul>
 *   <li><b>annotations</b> -- the strongest, but a test helper with no {@code @Test} method has
 *       none;</li>
 *   <li><b>naming</b> -- {@code *Test}, {@code Test*}, {@code *Tests}, {@code *IT}, the convention
 *       every build tool already keys on;</li>
 *   <li><b>origin</b> -- a {@code src/test} or {@code test-classes} path segment.</li>
 * </ul>
 * Sensenmann matches tests to libraries by edit distance on names and openly admits the ambiguous
 * cases are unsolved. We are not going to do better; what we can do is be generous about what counts
 * as a test, since the cost of a false positive here is only that a class stops being usable as
 * evidence of its own liveness.
 */
public final class TestClassifier {

    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            // JUnit 4
            "Lorg/junit/Test;",
            "Lorg/junit/Before;",
            "Lorg/junit/After;",
            "Lorg/junit/BeforeClass;",
            "Lorg/junit/AfterClass;",
            "Lorg/junit/runner/RunWith;",
            // JUnit 5
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/api/Nested;",
            "Lorg/junit/jupiter/api/BeforeEach;",
            "Lorg/junit/jupiter/api/AfterEach;",
            "Lorg/junit/jupiter/api/BeforeAll;",
            "Lorg/junit/jupiter/api/AfterAll;",
            "Lorg/junit/jupiter/api/extension/ExtendWith;",
            "Lorg/junit/jupiter/params/ParameterizedTest;",
            // TestNG
            "Lorg/testng/annotations/Test;",
            "Lorg/testng/annotations/BeforeMethod;",
            "Lorg/testng/annotations/AfterMethod;");

    /** Path segments that mean "this came out of a test source root". */
    private static final Set<String> TEST_PATH_SEGMENTS = Set.of("test-classes");

    private static final String TEST_SOURCE_PATH = "/src/test/";

    public boolean isTest(ClassModel cls) {
        return hasTestAnnotation(cls) || hasTestName(cls) || hasTestOrigin(cls);
    }

    private boolean hasTestAnnotation(ClassModel cls) {
        if (containsAny(cls.annotationDescriptors())) {
            return true;
        }
        for (MethodModel method : cls.methods()) {
            if (containsAny(method.annotationDescriptors())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(Set<String> descriptors) {
        for (String descriptor : descriptors) {
            if (TEST_ANNOTATIONS.contains(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTestName(ClassModel cls) {
        String simple = cls.simpleName();
        return simple.endsWith("Test")
                || simple.endsWith("Tests")
                || simple.endsWith("IT")
                || simple.startsWith("Test");
    }

    private boolean hasTestOrigin(ClassModel cls) {
        String origin = cls.originPath().replace('\\', '/');
        if (origin.contains(TEST_SOURCE_PATH)) {
            return true;
        }
        for (String segment : origin.split("/")) {
            if (TEST_PATH_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }
}
