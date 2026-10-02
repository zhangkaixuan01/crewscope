package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.RepositoryIndexKey;
import java.util.Optional;

/**
 * Read-only port over the ACTIVE generation of one repository index coordinate.
 * An empty result is an explicit "no matching generation" and callers must degrade
 * with {@code DegradationReasonCode.NO_MATCHING_GENERATION} — never fall back to a
 * default branch (M10-S01 §3.4). Activation and retention are I01's internal concern.
 */
public interface GenerationCatalog {

    Optional<GenerationSnapshot> findActiveGeneration(RepositoryIndexKey indexKey);
}
