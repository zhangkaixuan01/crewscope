package io.crewscope.domain.retrieval;

import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/**
 * Full identity of one repository index coordinate (M10-S01 §3.1): all six components
 * are mandatory. Two bindings of the same physical repository produce different keys, so
 * their indexes are mutually invisible; changing the chunking policy or the embedding
 * model revision also produces a different key and therefore a rebuilt generation.
 */
public record RepositoryIndexKey(
        OrganizationId organizationId,
        TeamId teamId,
        RepositoryBindingId repositoryBindingId,
        SourceCommit sourceCommit,
        ChunkingPolicyHash chunkingPolicyHash,
        EmbeddingModelRevision embeddingModelRevision) {

    public RepositoryIndexKey {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(repositoryBindingId, "repositoryBindingId");
        Objects.requireNonNull(sourceCommit, "sourceCommit");
        Objects.requireNonNull(chunkingPolicyHash, "chunkingPolicyHash");
        Objects.requireNonNull(embeddingModelRevision, "embeddingModelRevision");
    }

    /** Deterministic field-order serialization used for persistence keys by I01. */
    public String canonical() {
        return String.join("\u0000",
                "repository-index-key",
                organizationId.toString(),
                teamId.toString(),
                repositoryBindingId.toString(),
                sourceCommit.value(),
                chunkingPolicyHash.value(),
                embeddingModelRevision.modelKey(),
                Integer.toString(embeddingModelRevision.dimension()),
                Long.toString(embeddingModelRevision.revision()));
    }

    @Override
    public String toString() {
        return canonical();
    }
}
