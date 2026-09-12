package dev.auxin.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The schema hash is the agent's only defence against probing a class whose member list moved under
 * it. It has to be reproducible on a different machine, in a different JVM, in a different locale.
 */
class SchemaHashTest {

    @Test
    @DisplayName("the empty member list hashes to SHA-256 of the empty string")
    void emptyListIsTheEmptyStringDigest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SchemaHash.ofNameAndDescs(Collections.<String>emptyList()));
    }

    @Test
    @DisplayName("the canonical form is sorted name+desc joined by newline, with a trailing newline")
    void matchesTheDocumentedCanonicalForm() {
        // Independently reproducible: printf 'a()V\nb()I\n' | shasum -a 256
        assertEquals("7890bec4d78843a169f89272202aafd48b354c44416b50901260a68d6c31bdfe",
                SchemaHash.ofNameAndDescs(Arrays.asList("b()I", "a()V")));
    }

    @Test
    @DisplayName("the hash does not depend on the order the members were supplied in")
    void isOrderIndependent() {
        List<String> members = new ArrayList<>(Arrays.asList(
                "pick(Ljava/util/List;)Lcom/acme/Rate;", "<init>()V", "toString()Ljava/lang/String;",
                "apply(I)I", "Apply(I)I"));
        String expected = SchemaHash.ofNameAndDescs(members);

        Random random = new Random(7L);
        for (int i = 0; i < 50; i++) {
            Collections.shuffle(members, random);
            assertEquals(expected, SchemaHash.ofNameAndDescs(members));
        }
    }

    @Test
    @DisplayName("adding, removing or renaming a member changes the hash")
    void isSensitiveToMemberChanges() {
        String base = SchemaHash.ofNameAndDescs(Arrays.asList("a()V", "b()V"));
        assertNotEquals(base, SchemaHash.ofNameAndDescs(Arrays.asList("a()V", "b()V", "c()V")));
        assertNotEquals(base, SchemaHash.ofNameAndDescs(Collections.singletonList("a()V")));
        assertNotEquals(base, SchemaHash.ofNameAndDescs(Arrays.asList("a()V", "B()V")));
    }

    @Test
    @DisplayName("an overload change is visible even though the method name is unchanged")
    void isSensitiveToDescriptorChanges() {
        assertNotEquals(SchemaHash.ofNameAndDescs(Collections.singletonList("f(I)V")),
                SchemaHash.ofNameAndDescs(Collections.singletonList("f(J)V")));
    }

    @Test
    @DisplayName("of(ClassEntry) agrees with ofNameAndDescs and with ClassEntry.of")
    void classEntryOverloadAgrees() {
        List<MethodEntry> methods = Arrays.asList(
                MethodEntry.builder(0, "pick", "(Ljava/util/List;)Lcom/acme/Rate;").build());
        ClassEntry entry = ClassEntry.of("com.acme.RateSelector", "RateSelector.java", false, false, methods);

        // printf 'pick(Ljava/util/List;)Lcom/acme/Rate;\n' | shasum -a 256
        assertEquals("6ccae6b6fc57611e23e5d9085b424fae327eccc182818104fe9794107d8de077", entry.schemaHash());
        assertEquals(entry.schemaHash(), SchemaHash.of(entry));
    }
}
