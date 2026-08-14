package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical SHA-256 digest for runtime payloads stored outside PostgreSQL. */
public record RuntimeContentHash(String value) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public RuntimeContentHash {
        if (value == null) {
            throw new DomainValidationException("runtimeContentHash", "must not be null");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(value).matches()) {
            throw new DomainValidationException(
                    "runtimeContentHash", "must be a 64-character lowercase SHA-256 value");
        }
    }

    public static RuntimeContentHash sha256(String value) {
        if (value == null) {
            throw new DomainValidationException("runtimeContent", "must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return new RuntimeContentHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
