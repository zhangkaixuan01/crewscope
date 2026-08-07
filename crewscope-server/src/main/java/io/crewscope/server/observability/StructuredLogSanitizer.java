package io.crewscope.server.observability;

import java.util.Locale;
import java.util.Set;

/** Applies the shared field-level redaction and log-injection boundary. */
public final class StructuredLogSanitizer {

    public static final String REDACTED = "[REDACTED]";
    public static final int MAX_VALUE_LENGTH = 256;

    private static final Set<String> EXACT_SENSITIVE_FIELDS = Set.of(
            "authorization",
            "proxyauthorization",
            "cookie",
            "setcookie",
            "password",
            "passwd",
            "secret",
            "token",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "apikey",
            "privatekey",
            "keymaterial",
            "credential",
            "credentials",
            "ciphertext",
            "nonce",
            "authenticationtag",
            "gcmtag");

    private StructuredLogSanitizer() {}

    /** Redacts sensitive fields and normalizes control characters in safe values. */
    public static String sanitize(String fieldName, Object value) {
        if (isSensitiveField(fieldName)) {
            return REDACTED;
        }
        if (value == null) {
            return "";
        }
        String normalized = value.toString()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .strip();
        if (normalized.length() <= MAX_VALUE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_VALUE_LENGTH - 1) + "…";
    }

    /** Returns true for case- and separator-insensitive credential-bearing field names. */
    public static boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String canonical = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return EXACT_SENSITIVE_FIELDS.contains(canonical)
                || canonical.endsWith("password")
                || canonical.endsWith("secret")
                || canonical.endsWith("token")
                || canonical.endsWith("apikey")
                || canonical.endsWith("privatekey")
                || canonical.endsWith("keymaterial")
                || canonical.endsWith("credential")
                || canonical.endsWith("ciphertext");
    }
}
