package dev.auxin.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe-index rule is the one piece of logic where being wrong is silent: a shifted index
 * misattributes coverage and reports a live method as dead. These tests are correspondingly
 * paranoid.
 */
class ProbeIndexTest {

    private static MethodCandidate m(String cls, String name, String desc) {
        return MethodCandidate.builder(cls, name, desc).access(MethodEntry.ACCESS_PUBLIC).build();
    }

    @Test
    @DisplayName("indices are assigned by (class, name, descriptor) order, restarting at 0 per class")
    void numbersFromZeroWithinEachClass() {
        List<MethodCandidate> candidates = new ArrayList<>();
        candidates.add(m("com.acme.Beta", "zulu", "()V"));
        candidates.add(m("com.acme.Alpha", "beta", "()V"));
        candidates.add(m("com.acme.Alpha", "alpha", "()V"));
        candidates.add(m("com.acme.Beta", "alpha", "()V"));

        Map<String, List<MethodEntry>> assigned = ProbeIndex.assign(candidates);

        assertEquals(List.of("com.acme.Alpha", "com.acme.Beta"), new ArrayList<>(assigned.keySet()));
        assertEquals(List.of("alpha", "beta"), names(assigned.get("com.acme.Alpha")));
        assertEquals(List.of(0, 1), indices(assigned.get("com.acme.Alpha")));
        assertEquals(List.of("alpha", "zulu"), names(assigned.get("com.acme.Beta")));
        assertEquals(List.of(0, 1), indices(assigned.get("com.acme.Beta")));
    }

    @Test
    @DisplayName("overloads are ordered by descriptor, not by parameter count")
    void ordersOverloadsByDescriptor() {
        List<MethodCandidate> candidates = List.of(
                m("C", "f", "(Ljava/lang/String;)V"),
                m("C", "f", "()V"),
                m("C", "f", "(I)V"),
                m("C", "f", "(II)V"));

        List<MethodEntry> entries = ProbeIndex.assign(candidates).get("C");

        // "()V" < "(I)V" < "(II)V" < "(Ljava/lang/String;)V" in code-unit order.
        assertEquals(List.of("()V", "(I)V", "(II)V", "(Ljava/lang/String;)V"), descriptors(entries));
        assertEquals(List.of(0, 1, 2, 3), indices(entries));
    }

    @Test
    @DisplayName("lexicographic means String.compareTo, not locale collation")
    void usesCodeUnitOrderNotCollation() {
        // Under a locale-aware Collator '_' and case are folded differently; code-unit order puts
        // every upper-case letter before every lower-case one and '_' (0x5F) after 'Z' (0x5A).
        List<MethodCandidate> candidates = List.of(
                m("C", "_hidden", "()V"),
                m("C", "Zulu", "()V"),
                m("C", "alpha", "()V"));

        assertEquals(List.of("Zulu", "_hidden", "alpha"), names(ProbeIndex.assign(candidates).get("C")));
    }

    @Test
    @DisplayName("synthetic, bridge, <clinit>, abstract and native get no probe and consume no index")
    void skipsNonProbeableMethods() {
        List<MethodCandidate> candidates = List.of(
                MethodCandidate.builder("C", "aSynthetic", "()V").synthetic(true).build(),
                MethodCandidate.builder("C", "bBridge", "()V").bridge(true).build(),
                MethodCandidate.builder("C", "<clinit>", "()V").build(),
                MethodCandidate.builder("C", "cAbstract", "()V").isAbstract(true).build(),
                MethodCandidate.builder("C", "dNative", "()V").isNative(true).build(),
                m("C", "eReal", "()V"),
                m("C", "fReal", "()V"));

        List<MethodEntry> entries = ProbeIndex.assign(candidates).get("C");

        assertEquals(List.of("eReal", "fReal"), names(entries));
        assertEquals(List.of(0, 1), indices(entries));
        assertFalse(entries.get(0).synthetic());
    }

    @Test
    @DisplayName("a class with no probeable methods disappears from the assignment entirely")
    void dropsClassesWithNoProbeableMethods() {
        Map<String, List<MethodEntry>> assigned = ProbeIndex.assign(List.of(
                MethodCandidate.builder("I", "onlyAbstract", "()V").isAbstract(true).build()));
        assertTrue(assigned.isEmpty());
    }

    @Test
    @DisplayName("STABILITY: 200 shuffles of the same candidate set produce byte-identical output")
    void isStableUnderInputReordering() {
        List<MethodCandidate> candidates = new ArrayList<>();
        for (String cls : List.of("com.acme.Z", "com.acme.A", "com.acme.M")) {
            for (String name : List.of("run", "Run", "apply", "<init>", "toString", "a", "zzz")) {
                candidates.add(m(cls, name, "()V"));
                candidates.add(m(cls, name, "(I)V"));
            }
        }
        String expected = render(ProbeIndex.assign(candidates));

        Random random = new Random(20260912L);
        for (int i = 0; i < 200; i++) {
            List<MethodCandidate> shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled, random);
            assertEquals(expected, render(ProbeIndex.assign(shuffled)),
                    "probe index assignment changed after shuffle #" + i);
        }
    }

    @Test
    @DisplayName("STABILITY: adding a method only shifts indices at and after its insertion point")
    void insertingAMethodShiftsOnlyLaterIndices() {
        List<MethodCandidate> before = List.of(m("C", "a", "()V"), m("C", "c", "()V"));
        List<MethodCandidate> after = List.of(m("C", "a", "()V"), m("C", "b", "()V"), m("C", "c", "()V"));

        List<MethodEntry> b = ProbeIndex.assign(before).get("C");
        List<MethodEntry> a = ProbeIndex.assign(after).get("C");

        assertEquals(0, b.get(0).idx());
        assertEquals(0, a.get(0).idx());
        // "c" moved from slot 1 to slot 2 -- which is exactly why the class carries a schemaHash
        // and why the agent must refuse to reuse a stale manifest.
        assertEquals(1, b.get(1).idx());
        assertEquals(2, a.get(2).idx());
    }

    @Test
    @DisplayName("a duplicate (class, name, descriptor) triple is rejected, never silently merged")
    void rejectsDuplicates() {
        List<MethodCandidate> candidates = List.of(m("C", "f", "()V"), m("C", "f", "()V"));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> ProbeIndex.assign(candidates));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    @DisplayName("candidate flags are carried through to the emitted entry")
    void carriesFlagsThrough() {
        MethodCandidate candidate = MethodCandidate.builder("C", "f", "()V")
                .access(MethodEntry.ACCESS_PROTECTED)
                .line(47)
                .tier2(true)
                .dynamicallyObservable(true)
                .shortCircuitable(false)
                .build();

        MethodEntry entry = ProbeIndex.assign(List.of(candidate)).get("C").get(0);

        assertEquals(MethodEntry.ACCESS_PROTECTED, entry.access());
        assertEquals(47, entry.line());
        assertTrue(entry.tier2());
        assertTrue(entry.dynamicallyObservable());
        assertFalse(entry.shortCircuitable());
        assertEquals(MethodEntry.UNKNOWN_SCC, entry.sccId());
        assertEquals(null, entry.testOnlyReachable());
    }

    @Test
    @DisplayName("the returned structure is immutable")
    void returnsImmutableStructure() {
        Map<String, List<MethodEntry>> assigned = ProbeIndex.assign(List.of(m("C", "f", "()V")));
        assertThrows(UnsupportedOperationException.class, () -> assigned.remove("C"));
        assertThrows(UnsupportedOperationException.class, () -> assigned.get("C").clear());
    }

    private static List<String> names(List<MethodEntry> entries) {
        List<String> out = new ArrayList<>();
        for (MethodEntry e : entries) {
            out.add(e.name());
        }
        return out;
    }

    private static List<String> descriptors(List<MethodEntry> entries) {
        List<String> out = new ArrayList<>();
        for (MethodEntry e : entries) {
            out.add(e.desc());
        }
        return out;
    }

    private static List<Integer> indices(List<MethodEntry> entries) {
        List<Integer> out = new ArrayList<>();
        for (MethodEntry e : entries) {
            out.add(e.idx());
        }
        return out;
    }

    private static String render(Map<String, List<MethodEntry>> assigned) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<MethodEntry>> e : assigned.entrySet()) {
            sb.append(e.getKey()).append(":\n");
            for (MethodEntry m : e.getValue()) {
                sb.append("  ").append(m.idx()).append(' ').append(m.nameAndDesc()).append('\n');
            }
        }
        return sb.toString();
    }
}
