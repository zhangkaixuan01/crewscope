package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;

/** How many recent generations of one index coordinate are retained for rollback. */
public record GenerationRetentionPolicy(int maxRetainedGenerations) {

    public static final GenerationRetentionPolicy DEFAULT = new GenerationRetentionPolicy(2);

    public GenerationRetentionPolicy {
        if (maxRetainedGenerations < 1) {
            throw new DomainValidationException(
                    "generationRetentionPolicy.maxRetainedGenerations",
                    "must be at least one");
        }
    }
}
