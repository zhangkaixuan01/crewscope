package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Persistable SHA-256 proof of a Claim Token; the plaintext is never stored. */
public record ClaimTokenHash(String value) {

    public ClaimTokenHash {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new DomainValidationException(
                    "claimTokenHash", "must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return "[REDACTED_HASH]";
    }
}
