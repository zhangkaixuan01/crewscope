package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Persistable proof of a Task Token JTI; the plaintext JTI never enters the database. */
public record TaskTokenJtiHash(String value) {

    public TaskTokenJtiHash {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new DomainValidationException(
                    "taskToken.jtiHash", "must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return "[REDACTED_JTI_HASH]";
    }
}
