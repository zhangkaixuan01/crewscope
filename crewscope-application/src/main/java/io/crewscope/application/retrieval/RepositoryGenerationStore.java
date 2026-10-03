package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.UUID;

/**
 * Write-side Port for repository index generations (M10-I01b). The ACTIVE row itself is
 * the activation pointer (one partial unique index per {@code index_key}), so activation
 * is a single transaction: the new generation flips to ACTIVE, the previous ACTIVE row
 * retires, and generations beyond the retention window are purged together with their
 * chunk vectors. A failed build marks only its own generation FAILED and never touches
 * the ACTIVE row.
 */
public interface RepositoryGenerationStore {

    /**
     * Opens the BUILDING generation of one index coordinate for one job, or resumes the
     * BUILDING generation the same job already opened (attempt-safe); the build sequence
     * is monotonically increasing per index coordinate.
     */
    RepositoryGeneration open(RepositoryIndexKey indexKey, UUID jobId);

    /**
     * Single-transaction pointer flip on behalf of the given principal. Returns
     * {@code false} when the generation is no longer activatable (already terminal, or
     * raced by another activation).
     */
    boolean activate(
            RepositoryGenerationKey generationKey,
            GenerationRetentionPolicy retention,
            PrincipalId activatedBy);

    /** Marks one non-ACTIVE generation FAILED; never touches the ACTIVE row. */
    void fail(RepositoryGenerationKey generationKey);
}
