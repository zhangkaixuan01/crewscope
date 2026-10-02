package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical SHA-256 digest of the frozen chunking policy used by one index build. */
public record ChunkingPolicyHash(String value) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ChunkingPolicyHash {
        if (value == null) {
            throw new DomainValidationException("chunkingPolicyHash", "must not be null");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(value).matches()) {
            throw new DomainValidationException(
                    "chunkingPolicyHash", "must be a 64-character lowercase SHA-256 value");
        }
    }

    public static ChunkingPolicyHash sha256(String canonicalPolicy) {
        if (canonicalPolicy == null) {
            throw new DomainValidationException(
                    "chunkingPolicy", "must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalPolicy.getBytes(StandardCharsets.UTF_8));
            return new ChunkingPolicyHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
