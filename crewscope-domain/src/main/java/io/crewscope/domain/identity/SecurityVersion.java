package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Monotonic account security version embedded in sessions for immediate revocation checks. */
public record SecurityVersion(long value) implements Comparable<SecurityVersion> {

    public SecurityVersion {
        if (value < 1) {
            throw new DomainValidationException("userAccount.securityVersion", "must be positive");
        }
    }

    public static SecurityVersion initial() {
        return new SecurityVersion(1);
    }

    public SecurityVersion next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "userAccount.securityVersion", "must not overflow");
        }
        return new SecurityVersion(value + 1);
    }

    @Override
    public int compareTo(SecurityVersion other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
