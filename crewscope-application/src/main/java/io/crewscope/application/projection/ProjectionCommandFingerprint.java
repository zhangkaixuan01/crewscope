package io.crewscope.application.projection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** SHA-256 of safe command coordinates used to reject command-ID semantic reuse. */
public record ProjectionCommandFingerprint(String value) {

    public ProjectionCommandFingerprint {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Projection command fingerprint must be SHA-256");
        }
    }

    public static ProjectionCommandFingerprint sha256(String canonicalValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(canonicalValue, "canonicalValue")
                            .getBytes(StandardCharsets.UTF_8));
            return new ProjectionCommandFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
