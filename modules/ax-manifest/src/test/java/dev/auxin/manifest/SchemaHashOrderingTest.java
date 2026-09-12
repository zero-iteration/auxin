package dev.auxin.manifest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for a real integration bug: SchemaHash once sorted the CONCATENATED
 * {@code name+descriptor} string while ProbeIndex sorted the TUPLE {@code (name, descriptor)}.
 *
 * <p>The two orders differ whenever one method name is a prefix of another, because {@code '$'}
 * (0x24) and most identifier characters sort before {@code '('} (0x28). When they diverged, the
 * agent's computed hash did not match the manifest's, so EVERY class was skipped and coverage
 * came back silently empty -- indistinguishable from "this code is dead".
 */
class SchemaHashOrderingTest {

    private static final String[][] PREFIX_COLLIDING = {
        {"pick", "(Ljava/util/List;)V"},
        {"pick$inner", "()V"},
        {"pickBest", "()V"},
        {"get", "()Ljava/lang/Object;"},
        {"get$x", "()V"},
        {"getA", "()V"},
    };

    private static List<String> joined() {
        List<String> out = new ArrayList<String>();
        for (String[] m : PREFIX_COLLIDING) {
            out.add(m[0] + m[1]);
        }
        return out;
    }

    @Test
    @DisplayName("hash order matches ProbeIndex's (name, desc) tuple order, not concatenated order")
    void tupleOrderNotConcatenatedOrder() {
        List<String> tuple = joined();
        Collections.sort(tuple, SchemaHash.NAME_THEN_DESC);

        List<String> concatenated = joined();
        Collections.sort(concatenated);

        assertNotEquals(concatenated, tuple,
            "fixture must actually exercise the divergence, or this test proves nothing");
        assertEquals(
            Arrays.asList("get()Ljava/lang/Object;", "get$x()V", "getA()V",
                          "pick(Ljava/util/List;)V", "pick$inner()V", "pickBest()V"),
            tuple,
            "must order by (name, desc): 'get' before 'get$x' before 'getA'");
    }

    @Test
    @DisplayName("hash is independent of input order")
    void stableUnderShuffle() {
        String expected = SchemaHash.ofNameAndDescs(joined());
        List<String> shuffled = joined();
        for (int seed = 0; seed < 50; seed++) {
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(expected, SchemaHash.ofNameAndDescs(shuffled),
                "hash must not depend on the order members were visited in");
        }
    }

    @Test
    @DisplayName("a single differing member changes the hash")
    void sensitiveToMembership() {
        List<String> base = joined();
        List<String> plusOne = joined();
        plusOne.add("extra()V");
        assertNotEquals(SchemaHash.ofNameAndDescs(base), SchemaHash.ofNameAndDescs(plusOne));
    }
}
