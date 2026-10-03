package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import java.util.List;

/**
 * Write-side Persistence Port for repository chunk embeddings (vector chain table,
 * M10-I01b). Rows live in the pgvector database while generations live in the default
 * chain, so the two writes are ordered rather than atomic: vectors are committed first,
 * the checkpoint second, and a crash in between re-embeds the same batch onto the same
 * (generation, chunkSeq) positions — an idempotent overwrite, never a duplicate.
 * Read-side nearest-neighbor search is A01/I02, not this port.
 */
public interface RepositoryChunkVectorStore {

    /** Idempotent batch upsert keyed by (generation, chunkSeq). */
    void replaceBatch(RepositoryGenerationKey generation, List<RepositoryChunkVector> vectors);

    /** Removes every chunk row of one generation; returns the removed row count. */
    int deleteByGeneration(RepositoryGenerationKey generation);
}
