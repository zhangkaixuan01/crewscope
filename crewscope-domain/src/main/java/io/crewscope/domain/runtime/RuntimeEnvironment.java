package io.crewscope.domain.runtime;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Stable deployment boundary preventing Workers from being routed across environments. */
public record RuntimeEnvironment(String value) {

    private static final String PATTERN = "[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?";

    public RuntimeEnvironment {
        if (value == null) {
            throw new DomainValidationException("runtime.environment", "must not be null");
        }
        value = value.strip();
        if (!value.matches(PATTERN)) {
            throw new DomainValidationException(
                    "runtime.environment", "must be a lowercase stable environment key");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
