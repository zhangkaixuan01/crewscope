package io.crewscope.application.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical lower-case SHA-256 digest used for artifact integrity verification. */
public record Sha256Hash(String value) {

    private static final Pattern CANONICAL_SHA256 = Pattern.compile("[0-9a-f]{64}");

    public Sha256Hash {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SHA-256 hash must not be blank");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!CANONICAL_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "SHA-256 hash must contain exactly 64 hexadecimal characters");
        }
    }

    /** Calculates the digest of an in-memory value without retaining the original content. */
    public static Sha256Hash digest(byte[] content) {
        byte[] source = Objects.requireNonNull(content, "content");
        return new Sha256Hash(HexFormat.of().formatHex(newDigest().digest(source)));
    }

    /** Convenience overload for deterministic text fixtures and small metadata artifacts. */
    public static Sha256Hash digestUtf8(String content) {
        return digest(Objects.requireNonNull(content, "content").getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a fresh digest for streaming adapters such as Filesystem and S3 stores. */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JVM does not provide SHA-256", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
