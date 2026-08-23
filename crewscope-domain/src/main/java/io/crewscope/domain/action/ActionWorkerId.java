package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Bounded non-secret identity of one Action Worker process. */
public record ActionWorkerId(String value) {

    public static final int MAX_LENGTH = 200;

    public ActionWorkerId {
        if (value == null || value.isBlank() || value.strip().length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "actionDispatch.workerId", "must be non-blank and within the size limit");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return value;
    }
}
