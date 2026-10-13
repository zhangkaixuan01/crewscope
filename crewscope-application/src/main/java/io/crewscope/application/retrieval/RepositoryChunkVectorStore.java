package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;

/**
 * Persistence Port for repository chunk embeddings (vector chain table, M10-I01b write
 * side / M10-A01 read side). Rows live in the pgvector database while generations live in
 * the default chain, so the two writes are ordered rather than atomic: vectors are
 * committed first, the checkpoint second, and a crash in between re-embeds the same batch
 * onto the same (generation, chunkSeq) positions — an idempotent overwrite, never a
 * duplicate. Reads are constrained to one generation handed over by the caller, so the
 * ACTIVE-generation gate stays upstream in the retrieval service.
 */
public interface RepositoryChunkVectorStore {

    /** Idempotent batch upsert keyed by (generation, chunkSeq). */
    void replaceBatch(RepositoryGenerationKey generation, List<RepositoryChunkVector> vectors);

    /** Removes every chunk row of one generation; returns the removed row count. */
    int deleteByGeneration(RepositoryGenerationKey generation);

    /**
     * Nearest neighbors within exactly one generation (M10-A01). The adapter hardcodes
     * the tenant, binding and generation predicates ahead of the vector ordering, so no
     * caller can widen a search beyond the activated generation it was handed.
     */
    List<ScoredRepositoryChunk> nearest(RepositoryChunkEmbeddingQuery query);

    /**
     * Whether the pgvector store is deployed and readable — mirrors the knowledge store face
     * the governance export consults before settling vector families as SKIPPED.
     */
    boolean isEnabled();

    /**
     * Bounded keyset page of one organization's chunk vector rows ordered by
     * (index_key, build_sequence, chunk_seq) — the governance export face. The projection
     * carries raw columns because the stored index coordinate is a one-way hash.
     */
    List<RepositoryChunkEmbeddingExportRow> pageByOrganization(
            OrganizationId organizationId, String afterCursor, int limit);
}
