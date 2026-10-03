package io.crewscope.application.retrieval;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One stored embedding vector of one exact published knowledge entry revision, produced
 * by one exact embedding model revision (S01 §3.1). The content hash pins the vector to
 * the exact source text, so a re-embed of changed content is a new row, never a mutation.
 */
public record KnowledgeEmbeddingVector(
        OrganizationId organizationId,
        TeamId teamId,
        KnowledgeEntryId entryId,
        KnowledgeEntryRevision revision,
        EmbeddingModelRevision model,
        String contentHash,
        float[] embedding) {

    private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");

    public KnowledgeEmbeddingVector {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        entryId = Objects.requireNonNull(entryId, "entryId");
        revision = Objects.requireNonNull(revision, "revision");
        model = Objects.requireNonNull(model, "model");
        if (contentHash == null || !CONTENT_HASH.matcher(contentHash).matches()) {
            throw new DomainValidationException(
                    "knowledgeEmbedding.contentHash", "must be a 64-character hex hash");
        }
        if (embedding == null || embedding.length != model.dimension()) {
            throw new DomainValidationException(
                    "knowledgeEmbedding.embedding",
                    "must carry exactly the model revision dimension");
        }
        embedding = embedding.clone();
        for (float component : embedding) {
            if (!Float.isFinite(component)) {
                throw new DomainValidationException(
                        "knowledgeEmbedding.embedding", "must contain only finite components");
            }
        }
    }

    /** Defensive copy: the stored geometry never aliases a caller-owned buffer. */
    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
