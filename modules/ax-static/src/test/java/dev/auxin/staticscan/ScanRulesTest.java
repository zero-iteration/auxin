package dev.auxin.staticscan;

import dev.auxin.manifest.EdgeSemantics;
import dev.auxin.manifest.Resolution;
import dev.auxin.staticscan.api.PublicApiDetector;
import dev.auxin.staticscan.entry.EntryPointCatalog;
import dev.auxin.staticscan.graph.ResolvedEdge;
import dev.auxin.staticscan.linkage.MethodLinkage;
import dev.auxin.staticscan.linkage.TestClassifier;
import dev.auxin.staticscan.linkage.TestLinkageAnalyzer;
import dev.auxin.staticscan.model.ClassModel;
import dev.auxin.staticscan.model.MethodBodyShape;
import dev.auxin.staticscan.model.MethodModel;
import dev.auxin.staticscan.model.MethodRef;
import dev.auxin.staticscan.scan.DynamicObservability;
import dev.auxin.staticscan.scan.GeneratedClassFilter;
import dev.auxin.staticscan.scan.ShortCircuitCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the rules that are pure functions over the scanned model. The end-to-end test
 * proves they fire on real bytecode; these pin the edges of each rule, including the ones no
 * reasonable fixture would produce.
 */
class ScanRulesTest {

    // ---------------------------------------------------------------- generated classes

    @Test
    @DisplayName("runtime-generated classes are excluded from the inventory (jacoco#655)")
    void excludesRuntimeGeneratedClasses() {
        GeneratedClassFilter filter = new GeneratedClassFilter();
        for (String generated : List.of(
                "com.acme.Service$$EnhancerBySpringCGLIB$$1a2b3c",
                "com.acme.Service$$EnhancerByCGLIB$$ff",
                "com.acme.Service$$FastClassBySpringCGLIB$$00",
                "com.acme.Service$$SpringCGLIB$$0",
                "com.acme.Caller$$Lambda$14/0x00007f",
                "com.sun.proxy.$Proxy42",
                "jdk.internal.reflect.GeneratedMethodAccessor7",
                "jdk.internal.reflect.GeneratedConstructorAccessor3",
                "com.acme.Service$ByteBuddy$abc",
                "com.acme.Service$MockitoMock$123",
                "com.acme.Entity_$$_jvst1f_0")) {
            assertTrue(filter.isGenerated(generated), generated);
        }
        for (String real : List.of(
                "com.acme.Service",
                "com.acme.Outer$Inner",
                "com.acme.Outer$1",
                "com.acme.ProxyFactory",
                "com.acme.Proxy")) {
            assertFalse(filter.isGenerated(real), real);
        }
    }

    // ---------------------------------------------------------------- public API

    @Test
    @DisplayName("an internal or impl package segment overrides public visibility")
    void internalPackagesAreNotPublicApi() {
        PublicApiDetector detector = new PublicApiDetector();
        assertFalse(detector.isPublicApi(publicClass("com.acme.internal.Thing")));
        assertFalse(detector.isPublicApi(publicClass("com.acme.impl.Thing")));
        assertFalse(detector.isPublicApi(publicClass("com.acme.Impl.Thing")), "segment match is case-insensitive");
        assertTrue(detector.isPublicApi(publicClass("com.acme.internals.Thing")),
                "'internals' is not the convention; only an exact segment counts");
        assertTrue(detector.isPublicApi(publicClass("com.acme.Thing")));
        assertTrue(detector.isPublicApi(publicClass("Thing")), "the unnamed package cannot be internal");
    }

    @Test
    @DisplayName("a non-public class is not API surface")
    void packagePrivateClassesAreNotPublicApi() {
        assertFalse(new PublicApiDetector().isPublicApi(
                new ClassModel("com.acme.Hidden", "com/acme/Hidden", "Hidden.java", 0,
                        "java.lang.Object", List.of(), Set.of(), List.of(), "")));
    }

    // ---------------------------------------------------------------- test detection

    @Test
    @DisplayName("test classes are recognised by annotation, by name, and by origin path")
    void detectsTestCodeThreeWays() {
        TestClassifier classifier = new TestClassifier();

        assertTrue(classifier.isTest(new ClassModel("com.acme.Helper", "com/acme/Helper", "Helper.java",
                        Opcodes.ACC_PUBLIC, "java.lang.Object", List.of(), Set.of(),
                        List.of(method("run", "()V", Opcodes.ACC_PUBLIC,
                                Set.of("Lorg/junit/jupiter/api/Test;"))), "")),
                "a @Test method makes the class test code whatever it is called");

        assertTrue(classifier.isTest(publicClass("com.acme.RateSelectorTest")));
        assertTrue(classifier.isTest(publicClass("com.acme.RateSelectorTests")));
        assertTrue(classifier.isTest(publicClass("com.acme.RateSelectorIT")));
        assertTrue(classifier.isTest(publicClass("com.acme.TestSupport")));

        assertTrue(classifier.isTest(new ClassModel("com.acme.Plain", "com/acme/Plain", "Plain.java",
                        Opcodes.ACC_PUBLIC, "java.lang.Object", List.of(), Set.of(), List.of(),
                        "/repo/src/test/java/com/acme/Plain.java")),
                "a src/test origin is enough on its own");
        assertTrue(classifier.isTest(new ClassModel("com.acme.Plain", "com/acme/Plain", "Plain.java",
                        Opcodes.ACC_PUBLIC, "java.lang.Object", List.of(), Set.of(), List.of(),
                        "/repo/target/test-classes/com/acme/Plain.class")));

        assertFalse(classifier.isTest(publicClass("com.acme.RateSelector")));
        assertFalse(classifier.isTest(publicClass("com.acme.Latest")),
                "'Latest' ends in 'est', not 'Test'");
    }

    // ---------------------------------------------------------------- C51

    @Test
    @DisplayName("C51: the shapes that cannot be covered dynamically")
    void classifiesDynamicObservability() {
        DynamicObservability rule = new DynamicObservability();

        assertFalse(rule.isObservable(withBody("empty", "()V", shape(1, Opcodes.RETURN))));
        assertFalse(rule.isObservable(withBody("constant", "()I",
                shape(2, Opcodes.ICONST_0, Opcodes.IRETURN))));
        assertFalse(rule.isObservable(withBody("self", "()Ljava/lang/Object;",
                shape(2, Opcodes.ALOAD, Opcodes.ARETURN))));
        assertFalse(rule.isObservable(withBody("getName", "()Ljava/lang/String;",
                shape(3, Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.ARETURN))));

        assertTrue(rule.isObservable(withBody("compute", "()I",
                shape(9, Opcodes.ICONST_0, Opcodes.ISTORE, Opcodes.ILOAD, Opcodes.IRETURN))));

        assertFalse(rule.isObservable(new MethodModel("abstractOne", "()V",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, -1, Set.of(), MethodBodyShape.NO_BODY)));
        assertFalse(rule.isObservable(new MethodModel("nativeOne", "()V",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, -1, Set.of(), MethodBodyShape.NO_BODY)));
    }

    @Test
    @DisplayName("C51: a constructor that only delegates is not observable, one that assigns is")
    void classifiesConstructors() {
        DynamicObservability rule = new DynamicObservability();

        MethodBodyShape delegating = new MethodBodyShape(4,
                new int[] {Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.INVOKESPECIAL, Opcodes.RETURN},
                false, false, 1, 1, false, false);
        assertFalse(rule.isObservable(new MethodModel("<init>", "(Ljava/lang/String;)V",
                Opcodes.ACC_PUBLIC, 1, Set.of(), delegating)));

        MethodBodyShape assigning = new MethodBodyShape(6,
                new int[] {Opcodes.ALOAD, Opcodes.INVOKESPECIAL, Opcodes.ALOAD, Opcodes.ILOAD},
                true, false, 1, 1, false, false);
        assertTrue(rule.isObservable(new MethodModel("<init>", "(I)V",
                Opcodes.ACC_PUBLIC, 1, Set.of(), assigning)));
    }

    // ---------------------------------------------------------------- proxy short-circuit

    @Test
    @DisplayName("short-circuiting annotations match on simple name, across javax/jakarta/vendors")
    void matchesShortCircuitAnnotationsBySimpleName() {
        ShortCircuitCatalog catalog = new ShortCircuitCatalog();
        ClassModel plain = publicClass("com.acme.Service");

        for (String descriptor : List.of(
                "Lorg/springframework/cache/annotation/Cacheable;",
                "Ljavax/cache/annotation/CacheResult;",
                "Ljakarta/cache/annotation/CacheResult;",
                "Lio/github/resilience4j/circuitbreaker/annotation/CircuitBreaker;",
                "Lorg/springframework/retry/annotation/Retryable;",
                "Lcom/netflix/hystrix/contrib/javanica/annotation/HystrixCommand;",
                "Lorg/springframework/transaction/annotation/Transactional;",
                "Ljakarta/transaction/Transactional;")) {
            assertTrue(catalog.isShortCircuitable(plain,
                            method("call", "()V", Opcodes.ACC_PUBLIC, Set.of(descriptor))),
                    descriptor);
        }

        assertFalse(catalog.isShortCircuitable(plain,
                method("call", "()V", Opcodes.ACC_PUBLIC, Set.of("Lcom/acme/Audited;"))));
    }

    @Test
    @DisplayName("a type-level @Transactional makes every method short-circuitable")
    void appliesClassLevelShortCircuitAnnotations() {
        ClassModel annotated = new ClassModel("com.acme.Service", "com/acme/Service", "Service.java",
                Opcodes.ACC_PUBLIC, "java.lang.Object", List.of(),
                Set.of("Lorg/springframework/transaction/annotation/Transactional;"), List.of(), "");
        assertTrue(new ShortCircuitCatalog().isShortCircuitable(annotated,
                method("call", "()V", Opcodes.ACC_PUBLIC, Set.of())));
    }

    // ---------------------------------------------------------------- entry-point catalogue

    @Test
    @DisplayName("the catalogue maps descriptors to kinds and knows which apply to a type")
    void mapsEntryPointDescriptors() {
        EntryPointCatalog catalog = new EntryPointCatalog();
        assertEquals("KafkaListener",
                catalog.kindOf("Lorg/springframework/kafka/annotation/KafkaListener;"));
        assertEquals("PostConstruct", catalog.kindOf("Ljakarta/annotation/PostConstruct;"));
        assertEquals("PostConstruct", catalog.kindOf("Ljavax/annotation/PostConstruct;"));
        assertEquals("Path", catalog.kindOf("Ljakarta/ws/rs/Path;"));
        assertNull(catalog.kindOf("Lcom/acme/NotAnEntryPoint;"));

        assertTrue(catalog.appliesAtClassLevel("Lorg/springframework/web/bind/annotation/RequestMapping;"));
        assertTrue(catalog.appliesAtClassLevel("Ljavax/ws/rs/Path;"));
        assertFalse(catalog.appliesAtClassLevel("Lorg/springframework/context/annotation/Bean;"),
                "@Bean is declared @Target(METHOD); looking for it on a type is only noise");
    }

    // ---------------------------------------------------------------- C50 edge case

    @Test
    @DisplayName("C50: a production cycle nothing enters is null, not false and not true")
    void reportsUnenteredProductionCycleAsUnknown() {
        ClassModel a = classWith("com.acme.A", method("a", "()V", Opcodes.ACC_PUBLIC, Set.of()));
        ClassModel b = classWith("com.acme.B", method("b", "()V", Opcodes.ACC_PUBLIC, Set.of()));
        MethodRef refA = new MethodRef("com.acme.A", "a", "()V");
        MethodRef refB = new MethodRef("com.acme.B", "b", "()V");

        Map<MethodRef, MethodLinkage> linkage = new TestLinkageAnalyzer().analyse(
                List.of(a, b), Set.of(),
                List.of(new ResolvedEdge(refA, refB, Resolution.EXACT, EdgeSemantics.BLOCKING),
                        new ResolvedEdge(refB, refA, Resolution.EXACT, EdgeSemantics.BLOCKING)),
                Set.of());

        assertEquals(linkage.get(refA).sccId(), linkage.get(refB).sccId(),
                "a mutually recursive pair is one strongly-connected component");
        assertNull(linkage.get(refA).testOnlyReachable());
        assertNull(linkage.get(refB).testOnlyReachable());
    }

    @Test
    @DisplayName("C52: a no-op edge cannot be what keeps a method alive")
    void noOpEdgesDoNotEstablishLiveness() {
        ClassModel a = classWith("com.acme.A", method("a", "()V", Opcodes.ACC_PUBLIC, Set.of()));
        ClassModel b = classWith("com.acme.B", method("b", "()V", Opcodes.ACC_PUBLIC, Set.of()));
        MethodRef refA = new MethodRef("com.acme.A", "a", "()V");
        MethodRef refB = new MethodRef("com.acme.B", "b", "()V");

        Map<MethodRef, MethodLinkage> linkage = new TestLinkageAnalyzer().analyse(
                List.of(a, b), Set.of(),
                List.of(new ResolvedEdge(refA, refB, Resolution.EXACT, EdgeSemantics.NOOP)),
                Set.of());

        assertNull(linkage.get(refB).testOnlyReachable(),
                "the only inbound edge folds away with its target, so nothing is known");
    }

    // ---------------------------------------------------------------- helpers

    private static ClassModel publicClass(String name) {
        return new ClassModel(name, name.replace('.', '/'), "", Opcodes.ACC_PUBLIC,
                "java.lang.Object", List.of(), Set.of(), List.of(), "");
    }

    private static ClassModel classWith(String name, MethodModel... methods) {
        return new ClassModel(name, name.replace('.', '/'), "", Opcodes.ACC_PUBLIC,
                "java.lang.Object", List.of(), Set.of(), List.of(methods), "");
    }

    private static MethodModel method(String name, String desc, int access, Set<String> annotations) {
        return new MethodModel(name, desc, access, 1, annotations, MethodBodyShape.NO_BODY);
    }

    private static MethodModel withBody(String name, String desc, MethodBodyShape shape) {
        return new MethodModel(name, desc, Opcodes.ACC_PUBLIC, 1, Set.of(), shape);
    }

    private static MethodBodyShape shape(int instructionCount, int... leadingOpcodes) {
        return new MethodBodyShape(instructionCount, leadingOpcodes, false, false, 0, 0, false, false);
    }
}
