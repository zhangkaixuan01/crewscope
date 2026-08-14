package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** High-entropy JWT identifier returned only through the trusted token-signing boundary. */
public final class TaskTokenJti {

    private static final String PATTERN = "[A-Za-z0-9_-]{43,128}";
    private final String value;

    public TaskTokenJti(String value) {
        if (value == null || !value.matches(PATTERN)) {
            throw new DomainValidationException(
                    "taskToken.jti", "must be a 256-bit or stronger base64url value");
        }
        this.value = value;
    }

    /** Exposes the identifier only to the trusted signer creating the short-lived Task Token. */
    public String reveal() {
        return value;
    }

    public TaskTokenJtiHash hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return new TaskTokenJtiHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", failure);
        }
    }

    @Override
    public String toString() {
        return "[REDACTED_JTI]";
    }
}
