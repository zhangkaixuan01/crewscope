package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Optimistic version of the CredentialStore envelope bound to a model connection. */
public record ModelCredentialVersion(long value) implements Comparable<ModelCredentialVersion> {

    public ModelCredentialVersion {
        if (value < 0) {
            throw new DomainValidationException(
                    "modelConnection.credentialVersion", "must not be negative");
        }
    }

    public ModelCredentialVersion next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "modelConnection.credentialVersion", "must not overflow");
        }
        return new ModelCredentialVersion(value + 1);
    }

    @Override
    public int compareTo(ModelCredentialVersion other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
