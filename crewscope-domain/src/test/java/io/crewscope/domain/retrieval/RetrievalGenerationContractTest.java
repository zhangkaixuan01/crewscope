package io.crewscope.domain.retrieval;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

class RetrievalGenerationContractTest {

    @Test
    void generationStatusVocabularyIsFrozenAtExactlySixValues() {
        assertEquals(
                "[BUILDING, VALIDATING, ACTIVE, RETIRED, FAILED, CANCELLED]",
                java.util.Arrays.toString(GenerationStatus.values()));
    }

    @Test
    void retentionPolicyDefaultsToTwoAndRejectsNonPositiveCounts() {
        assertEquals(2, GenerationRetentionPolicy.DEFAULT.maxRetainedGenerations());
        assertEquals(1, new GenerationRetentionPolicy(1).maxRetainedGenerations());

        assertThrows(DomainValidationException.class, () -> new GenerationRetentionPolicy(0));
        assertThrows(DomainValidationException.class, () -> new GenerationRetentionPolicy(-1));
    }

    @Test
    void sourceCommitAcceptsSha1AndSha256HexAndNormalizesCase() {
        assertEquals(
                "0123456789abcdef0123456789abcdef01234567",
                new SourceCommit("0123456789ABCDEF0123456789abcdef01234567").value());

        String sha256Hex = "a".repeat(64);
        assertEquals(sha256Hex, new SourceCommit(sha256Hex).value());

        assertThrows(DomainValidationException.class, () -> new SourceCommit("a".repeat(39)));
        assertThrows(DomainValidationException.class, () -> new SourceCommit("a".repeat(65)));
        assertThrows(DomainValidationException.class, () -> new SourceCommit("z".repeat(40)));
        assertThrows(DomainValidationException.class, () -> new SourceCommit(null));
    }

    @Test
    void chunkingPolicyHashDigestsCanonicalPolicyTextDeterministically() {
        assertEquals(
                ChunkingPolicyHash.sha256("{\"split\":\"paragraph\",\"max\":320}"),
                ChunkingPolicyHash.sha256("{\"split\":\"paragraph\",\"max\":320}"));
        assertEquals(
                ChunkingPolicyHash.sha256("{\"split\":\"paragraph\",\"max\":320}"),
                new ChunkingPolicyHash(
                        ChunkingPolicyHash.sha256("{\"split\":\"paragraph\",\"max\":320}").value()));

        assertThrows(DomainValidationException.class, () -> new ChunkingPolicyHash("a".repeat(63)));
        assertThrows(DomainValidationException.class, () -> new ChunkingPolicyHash("z".repeat(64)));
    }

    @Test
    void embeddingModelRevisionRequiresModelDimensionAndRevision() {
        EmbeddingModelRevision baseline =
                new EmbeddingModelRevision("text-embedding-v4", 1024, 1L);
        assertEquals("text-embedding-v4", baseline.modelKey());
        assertEquals(1024, baseline.dimension());
        assertEquals(1L, baseline.revision());

        assertDoesNotThrow(() -> new EmbeddingModelRevision(" m ", 1, 1L));

        assertThrows(DomainValidationException.class,
                () -> new EmbeddingModelRevision(" ", 1024, 1L));
        assertThrows(DomainValidationException.class,
                () -> new EmbeddingModelRevision("text-embedding-v4", 0, 1L));
        assertThrows(DomainValidationException.class,
                () -> new EmbeddingModelRevision("text-embedding-v4", 1024, 0L));
    }

    @Test
    void generationKeySequencesMustBePositiveAndMonotonic() {
        RepositoryIndexKey indexKey = new RepositoryIndexKey(
                io.crewscope.domain.shared.id.OrganizationId.generate(),
                io.crewscope.domain.shared.id.TeamId.generate(),
                io.crewscope.domain.coding.RepositoryBindingId.generate(),
                new SourceCommit("0123456789abcdef0123456789abcdef01234567"),
                ChunkingPolicyHash.sha256("policy-v1"),
                new EmbeddingModelRevision("text-embedding-v4", 1024, 1L));

        RepositoryGenerationKey first = new RepositoryGenerationKey(indexKey, 1L);
        assertEquals(2L, first.next().buildSequence());
        assertEquals(indexKey, first.indexKey());

        assertThrows(DomainValidationException.class,
                () -> new RepositoryGenerationKey(indexKey, 0L));
        assertThrows(DomainValidationException.class,
                () -> new RepositoryGenerationKey(indexKey, Long.MAX_VALUE).next());
    }
}
