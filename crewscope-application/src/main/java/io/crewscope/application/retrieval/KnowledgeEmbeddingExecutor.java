package io.crewscope.application.retrieval;

import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;

/**
 * The index pipeline's seam onto the I01a embedding service: model resolution through
 * the full governance chain (no HTTP) and batch delivery with per-attempt usage facts.
 * {@code TeamEmbeddingService} implements this directly; the interface exists so the
 * index services stay testable against a fake delivery instead of the final service.
 */
public interface KnowledgeEmbeddingExecutor {

    /** The Team's currently usable embedding model through the governance chain. */
    EmbeddingModelRevision resolveModel(OrganizationId organizationId, TeamId teamId);

    /** Delivers one embedding batch, or throws after recording the spent usage facts. */
    EmbeddingBatchResult embed(TeamEmbeddingCommand command);
}
