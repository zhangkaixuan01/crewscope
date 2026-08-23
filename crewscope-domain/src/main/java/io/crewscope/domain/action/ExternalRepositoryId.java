package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Stable Provider repository identity; display names and URLs are not authorization identities. */
public record ExternalRepositoryId(String value) {

    public static final int MAX_LENGTH = 200;

    public ExternalRepositoryId {
        if (value == null || value.isBlank() || value.strip().length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "plannedAction.repositoryId", "must be a non-blank stable Provider identity");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return value;
    }
}
