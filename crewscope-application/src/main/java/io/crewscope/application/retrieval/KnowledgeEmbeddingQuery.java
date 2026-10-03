package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/**
 * Closed nearest-neighbor filter (S01 §3.4): the query exposes nothing but the exact
 * tenant coordinates, the one embedding model revision whose vectors may be searched and
 * the bounded top-K. Tenant predicates and the effective-revision gate are hardcoded in
 * the adapter, so no caller can widen a search beyond its Team's published knowledge.
 */
public record KnowledgeEmbeddingQuery(
        OrganizationId organizationId,
        TeamId teamId,
        EmbeddingModelRevision model,
        float[] queryVector,
        int topK) {

    /** Retrieval budget ceiling (S01 §3.4 default top-K with headroom for reranking). */
    public static final int MAX_TOP_K = 20;

    public KnowledgeEmbeddingQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        model = Objects.requireNonNull(model, "model");
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new DomainValidationException(
                    "knowledgeEmbeddingQuery.topK", "must be between 1 and " + MAX_TOP_K);
        }
        if (queryVector == null || queryVector.length != model.dimension()) {
            throw new DomainValidationException(
                    "knowledgeEmbeddingQuery.queryVector",
                    "must carry exactly the model revision dimension");
        }
        queryVector = queryVector.clone();
        for (float component : queryVector) {
            if (!Float.isFinite(component)) {
                throw new DomainValidationException(
                        "knowledgeEmbeddingQuery.queryVector",
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
