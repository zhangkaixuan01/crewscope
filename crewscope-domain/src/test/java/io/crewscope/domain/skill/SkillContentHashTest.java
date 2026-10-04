package io.crewscope.domain.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/**
 * Content addressing of the SKILL.md document. The revision number is excluded on
 * purpose: a rollback re-publishes historical content and its digest must stay equal —
 * that equality is the proof the content really is the historical bytes.
 */
final class SkillContentHashTest {

    @Test
    void isDeterministicOverTheDocumentOnly() {
        assertEquals(SkillContentHash.of("doc"), SkillContentHash.of("doc"));
        assertNotEquals(SkillContentHash.of("doc"), SkillContentHash.of("doc "));
        assertNotEquals(SkillContentHash.of("doc"), SkillContentHash.of("dock"));
    }

    @Test
    void rendersAsLowercaseSha256Hex() {
        assertEquals(64, SkillContentHash.of("doc").value().length());
        assertEquals(SkillContentHash.of("doc").value(),
                SkillContentHash.of("doc").value().toLowerCase());
    }

    @Test
    void normalizesCaseOnConstruction() {
        assertEquals(
                SkillContentHash.of("doc"),
                new SkillContentHash(SkillContentHash.of("doc").value().toUpperCase()));
    }

    @Test
    void rejectsMalformedValues() {
        assertThrows(DomainValidationException.class, () -> new SkillContentHash(null));
        assertThrows(DomainValidationException.class, () -> new SkillContentHash("abc"));
        assertThrows(
                DomainValidationException.class,
                () -> new SkillContentHash("g".repeat(64)));
    }
}
