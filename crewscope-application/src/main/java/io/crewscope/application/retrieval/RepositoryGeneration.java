package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.GenerationStatus;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import java.util.Objects;
import java.util.UUID;

/** One persisted repository index generation on its way through the write-side lifecycle. */
public record RepositoryGeneration(
        RepositoryGenerationKey generationKey,
        UUID jobId,
        GenerationStatus status) {

    public RepositoryGeneration {
        Objects.requireNonNull(generationKey, "generationKey");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(status, "status");
    }
}
