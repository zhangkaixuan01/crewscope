package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import org.junit.jupiter.api.Test;

/** The closed retrieval-filter objects refuse every widening shape at construction. */
final class KnowledgeEmbeddingRecordsTest {

    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final TeamId TEAM = TeamId.generate();
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 4, 1);
    private static final String HASH = "a".repeat(64);

    @Test
    void vectorMustCarryTheExactModelDimensionAndFiniteGeometry() {
        assertDoesNotThrow(() -> vector(new float[4]));
        assertThrows(RuntimeException.class, () -> vector(new float[3]));
        float[] nonFinite = new float[4];
        nonFinite[0] = Float.NaN;
        assertThrows(RuntimeException.class, () -> vector(nonFinite));
        assertThrows(RuntimeException.class, () -> new KnowledgeEmbeddingVector(
                ORGANIZATION, TEAM, KnowledgeEntryId.generate(),
                new KnowledgeEntryRevision(1), MODEL, "not-a-hash", new float[4]));
    }

    @Test
    void vectorGeometryIsDefensivelyCopied() {
        float[] embedding = {0.5f, 0.5f, 0.5f, 0.5f};
        KnowledgeEmbeddingVector vector = vector(embedding);
        embedding[0] = -1f;
        assertArrayEquals(new float[] {0.5f, 0.5f, 0.5f, 0.5f}, vector.embedding());
    }

    @Test
    void queryBoundsTopKToTheRetrievalBudgetCeiling() {
        float[] queryVector = new float[4];
        assertDoesNotThrow(() -> new KnowledgeEmbeddingQuery(
                ORGANIZATION, TEAM, MODEL, queryVector, KnowledgeEmbeddingQuery.MAX_TOP_K));
        assertThrows(RuntimeException.class, () -> new KnowledgeEmbeddingQuery(
                ORGANIZATION, TEAM, MODEL, queryVector, 0));
        assertThrows(RuntimeException.class, () -> new KnowledgeEmbeddingQuery(
                ORGANIZATION, TEAM, MODEL, queryVector, KnowledgeEmbeddingQuery.MAX_TOP_K + 1));
        assertThrows(RuntimeException.class, () -> new KnowledgeEmbeddingQuery(
                ORGANIZATION, TEAM, MODEL, new float[5], 5));
    }

    @Test
    void scoredHitCarriesAFiniteSimilarity() {
        assertDoesNotThrow(() -> new ScoredKnowledgeEmbedding(
                KnowledgeEntryId.generate(), new KnowledgeEntryRevision(1), HASH, 0.75d));
        assertThrows(IllegalArgumentException.class, () -> new ScoredKnowledgeEmbedding(
                KnowledgeEntryId.generate(), new KnowledgeEntryRevision(1), HASH, Double.NaN));
    }

    private static KnowledgeEmbeddingVector vector(float[] embedding) {
        return new KnowledgeEmbeddingVector(
                ORGANIZATION,
                TEAM,
                KnowledgeEntryId.generate(),
                new KnowledgeEntryRevision(1),
                MODEL,
                HASH,
                embedding);
    }
}
