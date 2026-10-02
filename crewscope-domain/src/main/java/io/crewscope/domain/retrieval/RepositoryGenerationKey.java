package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Identity of one index build: the index coordinate plus a monotonic build sequence. */
public record RepositoryGenerationKey(RepositoryIndexKey indexKey, long buildSequence) {

    public RepositoryGenerationKey {
        Objects.requireNonNull(indexKey, "indexKey");
        if (buildSequence < 1) {
            throw new DomainValidationException(
                    "repositoryGenerationKey.buildSequence", "must be positive");
        }
    }

    public RepositoryGenerationKey next() {
        if (buildSequence == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "repositoryGenerationKey.buildSequence", "must not overflow");
        }
        return new RepositoryGenerationKey(indexKey, buildSequence + 1);
    }
}
