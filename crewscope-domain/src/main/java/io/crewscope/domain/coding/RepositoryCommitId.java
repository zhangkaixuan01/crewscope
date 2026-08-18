package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Full lower-case SHA-1 Git commit identity resolved by baseline Preflight. */
public record RepositoryCommitId(String value) {

    public RepositoryCommitId {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{40}")) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.baselineCommit",
                    "must be a full lower-case 40-character Git commit ID");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
