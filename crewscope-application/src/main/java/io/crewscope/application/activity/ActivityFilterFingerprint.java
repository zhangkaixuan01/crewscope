package io.crewscope.application.activity;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical SHA-256 fingerprint of the normalized public Activity filter. */
public record ActivityFilterFingerprint(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public ActivityFilterFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ActivityFilterFingerprint must not be blank");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ActivityFilterFingerprint must be a lower-case SHA-256 value");
        }
    }
}
