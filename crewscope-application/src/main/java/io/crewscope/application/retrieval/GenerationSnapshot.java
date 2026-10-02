package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.shared.audit.AuditMetadata;
import java.util.Objects;

/**
 * Read-only description of one ACTIVE index generation (I01 owns the write side):
 * its full coordinate and when/by whom it became active.
 */
public record GenerationSnapshot(
        RepositoryGenerationKey generationKey,
        AuditMetadata activation) {

    public GenerationSnapshot {
        Objects.requireNonNull(generationKey, "generationKey");
        Objects.requireNonNull(activation, "activation");
    }
}
