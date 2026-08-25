package io.crewscope.application.audit;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical SHA-256 fingerprint of one normalized Audit filter. */
public record AuditFilterFingerprint(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public AuditFilterFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AuditFilterFingerprint must not be blank");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "AuditFilterFingerprint must be a lower-case SHA-256 value");
        }
    }
}
