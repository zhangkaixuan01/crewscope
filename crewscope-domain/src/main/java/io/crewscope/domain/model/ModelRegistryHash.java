package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical SHA-256 digest for immutable Model Registry facts. */
public record ModelRegistryHash(String value) {

    public ModelRegistryHash {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new DomainValidationException(
                    "modelRegistry.hash", "must be a lower-case SHA-256 digest");
        }
    }

    public static ModelRegistryHash sha256(String canonicalValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(canonicalValue, "canonicalValue")
                            .getBytes(StandardCharsets.UTF_8));
            return new ModelRegistryHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static void append(StringBuilder target, String value) {
        String requiredValue = Objects.requireNonNull(value, "value");
        target.append('|').append(requiredValue.length()).append(':').append(requiredValue);
    }

    @Override
    public String toString() {
        return value;
    }
}
