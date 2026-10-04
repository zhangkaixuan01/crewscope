package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/**
 * Closed nearest-neighbor filter over repository chunk embeddings (M10-A01, S01 §3.4):
 * the query exposes nothing but the one ACTIVE generation whose rows may be searched and
 * the bounded top-K. The generation key already pins the tenant coordinates, the binding,
 * the commit, the chunking policy and the embedding model revision, so the adapter's
 * tenant/generation predicates and the query vector's model slot are consistent by
 * construction — no caller can widen a search beyond one activated generation of its
 * own Team's binding.
 */
public record RepositoryChunkEmbeddingQuery(
        RepositoryGenerationKey generation,
        float[] queryVector,
        int topK) {

    /** Same retrieval budget ceiling as the knowledge-side query (S01 §3.4). */
    public static final int MAX_TOP_K = 20;

    public RepositoryChunkEmbeddingQuery {
        Objects.requireNonNull(generation, "generation");
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new DomainValidationException(
                    "repositoryChunkEmbeddingQuery.topK", "must be between 1 and " + MAX_TOP_K);
        }
        int dimension = generation.indexKey().embeddingModelRevision().dimension();
        if (queryVector == null || queryVector.length != dimension) {
            throw new DomainValidationException(
                    "repositoryChunkEmbeddingQuery.queryVector",
                    "must carry exactly the generation's model dimension");
        }
        queryVector = queryVector.clone();
        for (float component : queryVector) {
            if (!Float.isFinite(component)) {
                throw new DomainValidationException(
                        "repositoryChunkEmbeddingQuery.queryVector",
                        "must contain only finite components");
            }
        }
    }

    /** Defensive copy: the query geometry never aliases a caller-owned buffer. */
    @Override
    public float[] queryVector() {
        return queryVector.clone();
    }
}
