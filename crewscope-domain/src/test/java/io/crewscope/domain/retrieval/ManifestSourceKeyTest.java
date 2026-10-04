package io.crewscope.domain.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/**
 * M10-I02c: the stage-free source key carries the same provenance validation as the
 * manifest reference itself, so feedback and claimed references cannot address
 * evidence by a malformed identity.
 */
class ManifestSourceKeyTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void acceptsAWellFormedKey() {
        ManifestSourceKey key = new ManifestSourceKey(
                ManifestSourceType.KNOWLEDGE_ENTRY, " entry-1 ", 3, HASH);

        assertEquals(ManifestSourceType.KNOWLEDGE_ENTRY, key.type());
        assertEquals("entry-1", key.sourceId());
        assertEquals(3, key.version());
        assertEquals(HASH, key.contentHash());
    }

    @Test
    void normalizesAnUppercaseDigestToLowercase() {
        ManifestSourceKey key = new ManifestSourceKey(
                ManifestSourceType.MEMORY_PREFERENCE, "theme", 1, "A".repeat(64));

        assertEquals(HASH, key.contentHash());
    }

    @Test
    void rejectsABlankSourceId() {
        assertThrows(DomainValidationException.class, () -> new ManifestSourceKey(
                ManifestSourceType.REPOSITORY_CHUNK, "  ", 1, HASH));
    }

    @Test
    void rejectsANonPositiveVersion() {
        assertThrows(DomainValidationException.class, () -> new ManifestSourceKey(
                ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 0, HASH));
    }

    @Test
    void rejectsAShortDigest() {
        assertThrows(DomainValidationException.class, () -> new ManifestSourceKey(
                ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3, "a".repeat(63)));
    }

    @Test
    void stripsTheStageOffAManifestReference() {
        ManifestSourceKey key = ManifestSourceKey.of(new ManifestSourceRef(
                ManifestSourceType.SKILL_INSTRUCTION, "skill-1", 1, HASH,
                ManifestSourceStage.INJECTED));

        assertEquals(ManifestSourceType.SKILL_INSTRUCTION, key.type());
        assertEquals(1, key.version());
        assertEquals(HASH, key.contentHash());
    }
}
