package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Sensitive encoded password hash whose string representation is always redacted. */
public final class LocalPasswordHash {

    public static final int MAX_ENCODED_LENGTH = 2_048;

    private final String encodedValue;
    private final PasswordHashAlgorithm algorithm;

    public LocalPasswordHash(String encodedValue) {
        if (encodedValue == null || encodedValue.isBlank()) {
            throw invalid("must not be blank");
        }
        if (!encodedValue.equals(encodedValue.strip())
                || encodedValue.length() > MAX_ENCODED_LENGTH
                || encodedValue.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw invalid("must be a bounded printable ASCII encoding");
        }
        this.algorithm = PasswordHashAlgorithm.fromEncodedValue(encodedValue);
        if (encodedValue.length() <= algorithm.prefix().length() + 16) {
            throw invalid("must contain an encoded password hash body");
        }
        this.encodedValue = encodedValue;
    }

    /** Trusted authentication adapters must avoid logging the explicitly retrieved value. */
    public String encodedValue() {
        return encodedValue;
    }

    public PasswordHashAlgorithm algorithm() {
        return algorithm;
    }

    @Override
    public String toString() {
        return "LocalPasswordHash[REDACTED]";
    }

    private static DomainValidationException invalid(String reason) {
        return new DomainValidationException("localCredential.passwordHash", reason);
    }
}
