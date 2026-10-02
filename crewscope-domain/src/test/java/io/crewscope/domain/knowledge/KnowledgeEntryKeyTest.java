package io.crewscope.domain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

class KnowledgeEntryKeyTest {

    @Test
    void acceptsWellFormedKeys() {
        assertEquals("a", KnowledgeEntryKey.parse("a").value());
        assertEquals("a-b-2", KnowledgeEntryKey.parse("a-b-2").value());
        assertEquals("0start", KnowledgeEntryKey.parse("0start").value());
        assertEquals("k".repeat(63), KnowledgeEntryKey.parse("k".repeat(63)).value());
        // Trailing hyphens are inside the character class — pin the non-obvious boundary.
        assertEquals("trailing-", KnowledgeEntryKey.parse("trailing-").value());
    }

    @Test
    void rejectsMalformedKeysWithStableFieldDetails() {
        String[] rejected = {
                null,
                "",
                " ",
                "UpperCase",
                "under_score",
                "-leading",
                "k".repeat(64),
                "dot.not",
                "中文"
        };
        for (String candidate : rejected) {
            DomainValidationException failure = assertThrows(
                    DomainValidationException.class, () -> KnowledgeEntryKey.parse(candidate));
            assertEquals("knowledgeEntry.entryKey", failure.error().details().get("field"));
        }
    }
}
