package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Arrays;

/** Closed password-hash algorithms accepted by the local credential reader. */
public enum PasswordHashAlgorithm {
    ARGON2ID("argon2id", true),
    BCRYPT("bcrypt", false);

    private final String encodingId;
    private final boolean currentWriteAlgorithm;

    PasswordHashAlgorithm(String encodingId, boolean currentWriteAlgorithm) {
        this.encodingId = encodingId;
        this.currentWriteAlgorithm = currentWriteAlgorithm;
    }

    public String encodingId() {
        return encodingId;
    }

    public String prefix() {
        return "{" + encodingId + "}";
    }

    public boolean isCurrentWriteAlgorithm() {
        return currentWriteAlgorithm;
    }

    public static PasswordHashAlgorithm fromEncodedValue(String encodedValue) {
        if (encodedValue == null) {
            throw invalid();
        }
        return Arrays.stream(values())
                .filter(candidate -> encodedValue.startsWith(candidate.prefix()))
                .findFirst()
                .orElseThrow(PasswordHashAlgorithm::invalid);
    }

    private static DomainValidationException invalid() {
        return new DomainValidationException(
                "localCredential.algorithm", "is not an approved password hash algorithm");
    }
}
