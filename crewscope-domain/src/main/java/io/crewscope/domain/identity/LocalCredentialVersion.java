package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Monotonic password credential version used by compare-and-set rotation. */
public record LocalCredentialVersion(long value) implements Comparable<LocalCredentialVersion> {

    public LocalCredentialVersion {
        if (value < 1) {
            throw new DomainValidationException("localCredential.version", "must be positive");
        }
    }

    public static LocalCredentialVersion initial() {
        return new LocalCredentialVersion(1);
    }

    public LocalCredentialVersion next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException("localCredential.version", "must not overflow");
        }
        return new LocalCredentialVersion(value + 1);
    }

    @Override
    public int compareTo(LocalCredentialVersion other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
