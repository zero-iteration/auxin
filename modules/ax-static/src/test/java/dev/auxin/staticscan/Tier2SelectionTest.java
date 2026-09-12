package dev.auxin.staticscan;

import dev.auxin.manifest.EntryPoint;
import dev.auxin.manifest.MethodCandidate;
import dev.auxin.staticscan.model.MethodRef;
import dev.auxin.staticscan.tier2.MethodPattern;
import dev.auxin.staticscan.tier2.Tier2BudgetExceededException;
import dev.auxin.staticscan.tier2.Tier2Selection;
import dev.auxin.staticscan.tier2.Tier2Selector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier-2 selection rules as pure functions: glob semantics, eligibility, precedence, budget.
 *
 * <p>These are unit tests because each rule has a failure mode that costs either money or truth --
 * a wildcard that quietly crosses a package boundary enrols a whole tree of methods at ~80ns per
 * call, and one that quietly does not leaves a controller with no timings at all. Neither shows up
 * as an error anywhere.
 */
class Tier2SelectionTest {

    // ---------------------------------------------------------------- glob semantics

    @Test
    @DisplayName("'*' stays inside one package segment; '**' crosses them")
    void starDoesNotCrossPackageBoundariesAndDoubleStarDoes() {
        MethodPattern single = MethodPattern.compile("com.acme.*.Handler#run");
        assertTrue(single.matches("com.acme.web.Handler", "run"), "one segment is one segment");
        assertFalse(single.matches("com.acme.web.api.Handler", "run"),
                "'*' must not swallow a package boundary");
        assertFalse(single.matches("com.acme.Handler", "run"), "'*' needs a segment to match");

        MethodPattern doubled = MethodPattern.compile("com.acme.**.Handler#run");
        assertTrue(doubled.matches("com.acme.web.Handler", "run"));
        assertTrue(doubled.matches("com.acme.web.api.deep.Handler", "run"),
                "'**' must cross package boundaries");
        assertTrue(doubled.matches("com.acme.Handler", "run"),
                "'**.' matches zero segments, as in every Ant-style glob");
        assertFalse(doubled.matches("com.other.web.Handler", "run"));
    }

    @Test
    @DisplayName("the documented example patterns select what the documentation says they do")
    void matchesTheDocumentedExamples() {
        MethodPattern repositories = MethodPattern.compile("com.acme.**.*Repository#*");
        assertTrue(repositories.matches("com.acme.orders.OrderRepository", "findById"));
        assertTrue(repositories.matches("com.acme.OrderRepository", "save"));
        assertTrue(repositories.matches("com.acme.a.b.c.Repository", "save"),
                "'*Repository' may match zero leading characters");
        assertFalse(repositories.matches("com.acme.orders.OrderRepositoryImpl", "findById"),
                "the glob is anchored at both ends");

        MethodPattern exact = MethodPattern.compile("com.acme.pricing.RateSelector#pick");
        assertTrue(exact.matches("com.acme.pricing.RateSelector", "pick"));
        assertFalse(exact.matches("com.acme.pricing.RateSelector", "picker"));
        assertFalse(exact.matches("com.acme.pricing.RateSelectorX", "pick"));
    }

    @Test
    @DisplayName("'**#*' matches everything, which is how tier-2 is switched off")
    void everythingPatternMatchesEverything() {
        MethodPattern all = MethodPattern.compile("**#*");
        assertTrue(all.matches("com.acme.Foo", "bar"));
        assertTrue(all.matches("Foo", "<init>"));
    }

    @Test
    @DisplayName("'$' is an ordinary character, so a nested class is matched by '*'")
    void treatsNestedClassesAsClasses() {
        assertTrue(MethodPattern.compile("com.acme.*#run").matches("com.acme.Outer$Inner", "run"));
        assertTrue(MethodPattern.compile("com.acme.Outer$Inner#run")
                .matches("com.acme.Outer$Inner", "run"));
    }

    @Test
    @DisplayName("a pattern is never read as a regular expression")
    void doesNotInterpretRegexMetacharacters() {
        assertFalse(MethodPattern.compile("com.acme.Foo#bar").matches("comXacmeXFoo", "bar"),
                "'.' is a literal dot, not 'any character'");
        assertFalse(MethodPattern.compile("com.acme.F+#bar").matches("com.acme.FFF", "bar"));
    }

    @Test
    @DisplayName("a malformed pattern is rejected with a message that shows the right shape")
    void rejectsMalformedPatterns() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> MethodPattern.compile("com.acme.Foo")).getMessage()
                        .contains("<class-glob>#<method-glob>"),
                "a bare class name must not be guessed to mean '#*'");
        assertThrows(IllegalArgumentException.class, () -> MethodPattern.compile("a#b#c"));
        assertThrows(IllegalArgumentException.class, () -> MethodPattern.compile("#pick"));
        assertThrows(IllegalArgumentException.class, () -> MethodPattern.compile("com.acme.Foo#"));
        assertThrows(IllegalArgumentException.class, () -> MethodPattern.compile(""));
    }

    // ---------------------------------------------------------------- automatic selection

    @Test
    @DisplayName("every eligible entry point is tier-2 with no configuration at all")
    void selectsEveryEntryPointByDefault() {
        Tier2Selection selection = Tier2Selector.automatic().select(
                List.of(observable("com.acme.Controller", "quote", "(I)I"),
                        observable("com.acme.Controller", "helper", "()V"),
                        observable("com.acme.Listener", "onEvent", "(Ljava/lang/Object;)V")),
                List.of(new EntryPoint("com.acme.Controller", "quote", "(I)I", "GetMapping"),
                        new EntryPoint("com.acme.Listener", "onEvent", "(Ljava/lang/Object;)V",
                                "KafkaListener")),
                Set.of());

        assertEquals(2, selection.count());
        assertEquals("entryPoint:GetMapping",
                selection.reasonFor("com.acme.Controller", "quote", "(I)I"));
        assertEquals("entryPoint:KafkaListener",
                selection.reasonFor("com.acme.Listener", "onEvent", "(Ljava/lang/Object;)V"));
        assertNull(selection.reasonFor("com.acme.Controller", "helper", "()V"),
                "a method that is not an entry point is not timed by default");
    }

    @Test
    @DisplayName("a kind written on the method beats one propagated from the type")
    void prefersTheMoreSpecificEntryPointKind() {
        Tier2Selection selection = Tier2Selector.automatic().select(
                List.of(observable("com.acme.Controller", "refresh", "()V"),
                        observable("com.acme.Controller", "quote", "(I)I")),
                List.of(
                        // The order here is the order the detector happens to produce; the reason
                        // must not depend on it.
                        new EntryPoint("com.acme.Controller", "refresh", "()V", "RequestMapping"),
                        new EntryPoint("com.acme.Controller", "refresh", "()V", "Scheduled"),
                        new EntryPoint("com.acme.Controller", "quote", "(I)I", "RequestMapping"),
                        new EntryPoint("com.acme.Controller", "quote", "(I)I", "GetMapping")),
                Set.of());

        assertEquals("entryPoint:Scheduled",
                selection.reasonFor("com.acme.Controller", "refresh", "()V"),
                "a scheduled job is not an HTTP endpoint just because its class carries a mapping");
        assertEquals("entryPoint:GetMapping",
                selection.reasonFor("com.acme.Controller", "quote", "(I)I"));
    }

    @Test
    @DisplayName("an entry point on a class that is not in this artifact selects nothing")
    void ignoresEntryPointsWithNoCandidate() {
        Tier2Selection selection = Tier2Selector.automatic().select(
                List.of(observable("com.acme.Present", "run", "()V")),
                List.of(new EntryPoint("com.acme.Absent", "<init>", "()V", "ServiceLoader")),
                Set.of());
        assertTrue(selection.isEmpty());
    }

    // ---------------------------------------------------------------- explicit selection

    @Test
    @DisplayName("a pattern selects matching methods and records the pattern as the reason")
    void selectsPatternMatches() {
        Tier2Selection selection = selector(List.of("com.acme.**.*Repository#*"), List.of(), 250)
                .select(List.of(
                        observable("com.acme.orders.OrderRepository", "findById", "(I)V"),
                        observable("com.acme.orders.OrderRepository", "save", "(I)V"),
                        observable("com.acme.orders.OrderService", "place", "()V")),
                        List.of(), Set.of());

        assertEquals(2, selection.count());
        assertEquals("pattern:com.acme.**.*Repository#*",
                selection.reasonFor("com.acme.orders.OrderRepository", "findById", "(I)V"));
        assertNull(selection.reasonFor("com.acme.orders.OrderService", "place", "()V"));
    }

    @Test
    @DisplayName("a method that is both an entry point and pattern-matched reports the entry point")
    void entryPointReasonWinsOverPattern() {
        Tier2Selection selection = selector(List.of("com.acme.**#*"), List.of(), 250)
                .select(List.of(observable("com.acme.Controller", "quote", "(I)I")),
                        List.of(new EntryPoint("com.acme.Controller", "quote", "(I)I", "GetMapping")),
                        Set.of());
        assertEquals("entryPoint:GetMapping",
                selection.reasonFor("com.acme.Controller", "quote", "(I)I"));
    }

    @Test
    @DisplayName("the first --tier2 pattern that matches is the one reported")
    void firstMatchingPatternIsTheReason() {
        Tier2Selection selection =
                selector(List.of("com.acme.*#pick", "com.acme.**#*"), List.of(), 250)
                        .select(List.of(observable("com.acme.Selector", "pick", "()V")),
                                List.of(), Set.of());
        assertEquals("pattern:com.acme.*#pick",
                selection.reasonFor("com.acme.Selector", "pick", "()V"));
    }

    @Test
    @DisplayName("a pattern that matched nothing is reported, not swallowed")
    void reportsUnmatchedPatterns() {
        Tier2Selection selection =
                selector(List.of("com.acme.Typo#*", "com.acme.Real#*"), List.of("com.acme.Nope#*"), 250)
                        .select(List.of(observable("com.acme.Real", "run", "()V")),
                                List.of(), Set.of());
        assertEquals(List.of("com.acme.Typo#*"), selection.unmatchedIncludes());
        assertEquals(List.of("com.acme.Nope#*"), selection.unmatchedExcludes());
    }

    // ---------------------------------------------------------------- excludes

    @Test
    @DisplayName("--tier2-exclude is applied after the includes")
    void excludeIsAppliedAfterInclude() {
        Tier2Selection selection = selector(
                List.of("com.acme.**#*"), List.of("com.acme.**#*Internal"), 250)
                .select(List.of(
                        observable("com.acme.Svc", "quote", "()V"),
                        observable("com.acme.Svc", "quoteInternal", "()V")),
                        List.of(), Set.of());

        assertEquals(1, selection.count());
        assertEquals("pattern:com.acme.**#*", selection.reasonFor("com.acme.Svc", "quote", "()V"));
        assertNull(selection.reasonFor("com.acme.Svc", "quoteInternal", "()V"),
                "the exclude must win over the include that also matched");
    }

    @Test
    @DisplayName("--tier2-exclude can also drop an automatically selected entry point")
    void excludeCanDropAnEntryPoint() {
        Tier2Selection selection = selector(List.of(), List.of("com.acme.**#health"), 250)
                .select(List.of(observable("com.acme.Health", "health", "()V"),
                                observable("com.acme.Orders", "place", "()V")),
                        List.of(new EntryPoint("com.acme.Health", "health", "()V", "GetMapping"),
                                new EntryPoint("com.acme.Orders", "place", "()V", "PostMapping")),
                        Set.of());

        assertEquals(1, selection.count());
        assertNull(selection.reasonFor("com.acme.Health", "health", "()V"),
                "the automatic half must be overridable or a noisy endpoint can never be silenced");
        assertEquals("entryPoint:PostMapping",
                selection.reasonFor("com.acme.Orders", "place", "()V"));
    }

    @Test
    @DisplayName("'**#*' as an exclude switches tier-2 off entirely")
    void excludeEverythingDisablesTier2() {
        Tier2Selection selection = selector(List.of("com.acme.**#*"), List.of("**#*"), 250)
                .select(List.of(observable("com.acme.Svc", "quote", "()V")),
                        List.of(new EntryPoint("com.acme.Svc", "quote", "()V", "GetMapping")),
                        Set.of());
        assertTrue(selection.isEmpty());
    }

    // ---------------------------------------------------------------- what can never be tier-2

    @Test
    @DisplayName("C51: a method that cannot be covered dynamically is never tier-2")
    void neverSelectsMethodsThatAreNotDynamicallyObservable() {
        MethodCandidate accessor = candidate("com.acme.Rate", "cents", "()I")
                .dynamicallyObservable(false)
                .build();
        Tier2Selection selection = selector(List.of("**#*"), List.of(), 250)
                .select(List.of(accessor),
                        List.of(new EntryPoint("com.acme.Rate", "cents", "()I", "GetMapping")),
                        Set.of());
        assertTrue(selection.isEmpty(),
                "timing a method whose execution is not distinguishable at runtime is cost for "
                        + "no signal -- and neither an explicit pattern nor an entry point may "
                        + "override that");
    }

    @Test
    @DisplayName("<clinit>, synthetic, bridge, abstract and native methods are never tier-2")
    void neverSelectsMembersThatCarryNoProbe() {
        List<MethodCandidate> candidates = List.of(
                candidate("com.acme.Svc", "<clinit>", "()V").dynamicallyObservable(true).build(),
                candidate("com.acme.Svc", "lambda$run$0", "()V")
                        .dynamicallyObservable(true).synthetic(true).build(),
                candidate("com.acme.Svc", "compareTo", "(Ljava/lang/Object;)I")
                        .dynamicallyObservable(true).bridge(true).build(),
                candidate("com.acme.Svc", "mustImplement", "()V")
                        .dynamicallyObservable(true).isAbstract(true).build(),
                candidate("com.acme.Svc", "goesNative", "()V")
                        .dynamicallyObservable(true).isNative(true).build(),
                observable("com.acme.Svc", "real", "()V"));

        Tier2Selection selection =
                selector(List.of("**#*"), List.of(), 250).select(candidates, List.of(), Set.of());

        assertEquals(1, selection.count(), "only the real method may be selected: " + selection.reasons());
        assertEquals("pattern:**#*", selection.reasonFor("com.acme.Svc", "real", "()V"));
        assertFalse(selection.isTier2("com.acme.Svc", "<clinit>", "()V"));
        assertFalse(selection.isTier2("com.acme.Svc", "lambda$run$0", "()V"));
        assertFalse(selection.isTier2("com.acme.Svc", "compareTo", "(Ljava/lang/Object;)I"));
        assertFalse(selection.isTier2("com.acme.Svc", "mustImplement", "()V"));
        assertFalse(selection.isTier2("com.acme.Svc", "goesNative", "()V"));
    }

    @Test
    @DisplayName("C50: a test class is never tier-2, however it was selected")
    void neverSelectsTestClasses() {
        Tier2Selection selection = selector(List.of("com.acme.**#*"), List.of(), 250)
                .select(List.of(observable("com.acme.SvcTest", "picksCheapest", "()V"),
                                observable("com.acme.Svc", "pick", "()V")),
                        List.of(new EntryPoint("com.acme.SvcTest", "main",
                                "([Ljava/lang/String;)V", "main")),
                        Set.of("com.acme.SvcTest"));

        assertEquals(1, selection.count());
        assertEquals("pattern:com.acme.**#*", selection.reasonFor("com.acme.Svc", "pick", "()V"));
        assertFalse(selection.isTier2("com.acme.SvcTest", "picksCheapest", "()V"),
                "a test's throughput is not the application's throughput");
    }

    @Test
    @DisplayName("a shortCircuitable method MAY be tier-2: that rule is about dead code, not timing")
    void selectsShortCircuitableMethods() {
        MethodCandidate cacheable = candidate("com.acme.CachingRates", "lookup", "(I)I")
                .dynamicallyObservable(true)
                .shortCircuitable(true)
                .build();
        Tier2Selection selection = selector(List.of("com.acme.CachingRates#lookup"), List.of(), 250)
                .select(List.of(cacheable), List.of(), Set.of());
        assertEquals("pattern:com.acme.CachingRates#lookup",
                selection.reasonFor("com.acme.CachingRates", "lookup", "(I)I"),
                "the calls that do reach the body are real calls with real latency");
    }

    // ---------------------------------------------------------------- budget

    @Test
    @DisplayName("exceeding --tier2-max fails the selection and names the top contributors")
    void budgetOverrunFailsLoudly() {
        List<MethodCandidate> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(observable("com.acme.wide.Svc", "m" + i, "()V"));
        }
        many.add(observable("com.acme.Controller", "quote", "()V"));

        Tier2BudgetExceededException e = assertThrows(Tier2BudgetExceededException.class,
                () -> selector(List.of("com.acme.wide.**#*"), List.of(), 10).select(many,
                        List.of(new EntryPoint("com.acme.Controller", "quote", "()V", "GetMapping")),
                        Set.of()));

        assertEquals(41, e.selected());
        assertEquals(10, e.max());
        String message = e.getMessage();
        assertTrue(message.contains("41"), message);
        assertTrue(message.contains("--tier2-max 10"), message);
        assertTrue(message.contains("pattern:com.acme.wide.**#*"), message);
        assertTrue(message.contains("entryPoint:GetMapping"), message);
        assertTrue(message.contains("top contributors"), message);
        assertTrue(message.contains("80ns"), "the cost is why the budget exists");
    }

    @Test
    @DisplayName("a selection exactly at the budget is allowed; one over is not")
    void budgetBoundaryIsInclusive() {
        List<MethodCandidate> two = List.of(
                observable("com.acme.Svc", "a", "()V"), observable("com.acme.Svc", "b", "()V"));
        assertEquals(2, selector(List.of("**#*"), List.of(), 2).select(two, List.of(), Set.of()).count());
        assertThrows(Tier2BudgetExceededException.class,
                () -> selector(List.of("**#*"), List.of(), 1).select(two, List.of(), Set.of()));
    }

    @Test
    @DisplayName("excludes are applied before the budget is checked")
    void excludesCountAgainstNothing() {
        List<MethodCandidate> two = List.of(
                observable("com.acme.Svc", "keep", "()V"), observable("com.acme.Svc", "drop", "()V"));
        Tier2Selection selection = selector(List.of("**#*"), List.of("**#drop"), 1)
                .select(two, List.of(), Set.of());
        assertEquals(1, selection.count());
    }

    @Test
    @DisplayName("the default budget is the documented 250")
    void defaultBudgetIsDocumented() {
        assertEquals(250, Tier2Selector.DEFAULT_MAX);
    }

    // ---------------------------------------------------------------- reporting

    @Test
    @DisplayName("the breakdown is ordered by count, and the selection order is stable")
    void reportsADeterministicBreakdown() {
        List<MethodCandidate> candidates = List.of(
                observable("com.acme.b.Svc", "one", "()V"),
                observable("com.acme.b.Svc", "two", "()V"),
                observable("com.acme.a.Controller", "quote", "()V"));

        Tier2Selection selection = selector(List.of("com.acme.b.**#*"), List.of(), 250)
                .select(candidates,
                        List.of(new EntryPoint("com.acme.a.Controller", "quote", "()V", "GetMapping")),
                        Set.of());

        assertEquals(List.of("pattern:com.acme.b.**#*", "entryPoint:GetMapping"),
                List.copyOf(selection.breakdown().keySet()));
        assertEquals(Map.of("pattern:com.acme.b.**#*", 2, "entryPoint:GetMapping", 1),
                selection.breakdown());
        assertEquals(List.of(
                        new MethodRef("com.acme.a.Controller", "quote", "()V"),
                        new MethodRef("com.acme.b.Svc", "one", "()V"),
                        new MethodRef("com.acme.b.Svc", "two", "()V")),
                List.copyOf(selection.reasons().keySet()),
                "sorted by (class, method, descriptor) so the manifest is reproducible");
    }

    @Test
    @DisplayName("an empty selection has no reason for anything")
    void emptySelectionIsEmpty() {
        assertTrue(Tier2Selection.empty().isEmpty());
        assertEquals(0, Tier2Selection.empty().count());
        assertNull(Tier2Selection.empty().reasonFor("com.acme.Svc", "run", "()V"));
    }

    // ---------------------------------------------------------------- helpers

    private static Tier2Selector selector(List<String> includes, List<String> excludes, int max) {
        return Tier2Selector.of(includes, excludes, max);
    }

    private static MethodCandidate observable(String cls, String name, String desc) {
        return candidate(cls, name, desc).dynamicallyObservable(true).build();
    }

    private static MethodCandidate.Builder candidate(String cls, String name, String desc) {
        return MethodCandidate.builder(cls, name, desc).access("public");
    }
}
