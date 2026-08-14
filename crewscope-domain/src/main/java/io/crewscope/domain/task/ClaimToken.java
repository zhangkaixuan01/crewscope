package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One-time claim secret returned only to the successful Worker. */
public final class ClaimToken {

    private static final String PATTERN = "[A-Za-z0-9_-]{43,128}";
    private final String value;

    public ClaimToken(String value) {
        if (value == null || !value.matches(PATTERN)) {
            throw new DomainValidationException(
                    "claimToken", "must be a 256-bit or stronger base64url secret");
        }
        this.value = value;
    }

    /** Exposes the secret only at the trusted Worker response boundary. */
    public String reveal() {
        return value;
    }

    public ClaimTokenHash hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return new ClaimTokenHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
