package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical lower-case SHA-256 for immutable M3 execution facts. */
public record TaskFactHash(String value) {
    public TaskFactHash {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new DomainValidationException(
                    "taskFactHash", "must be a lowercase SHA-256 digest");
        }
    }

    public static TaskFactHash sha256(String canonicalValue) {
        try {
            return new TaskFactHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(canonicalValue, "canonicalValue")
                            .getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
