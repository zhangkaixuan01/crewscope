package io.crewscope.domain.action;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.shared.error.DomainValidationException;

/** Full Git branch reference used by push preconditions. */
public record RepositoryBranchReference(String value) {

    private static final String PREFIX = "refs/heads/";

    public RepositoryBranchReference {
        if (value == null || !value.startsWith(PREFIX)) {
            throw new DomainValidationException(
                    "plannedAction.branch", "must be a full refs/heads Git branch reference");
        }
        // Reuse the established Git short-name validator for the suffix.
        new RepositoryBranchName(value.substring(PREFIX.length()));
    }

    public RepositoryBranchName shortName() {
        return new RepositoryBranchName(value.substring(PREFIX.length()));
    }

    @Override
    public String toString() {
        return value;
    }
}
