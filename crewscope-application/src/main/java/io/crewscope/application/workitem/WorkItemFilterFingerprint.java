package io.crewscope.application.workitem;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical SHA-256 fingerprint of one normalized WorkItem list filter. */
public record WorkItemFilterFingerprint(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public WorkItemFilterFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WorkItemFilterFingerprint must not be blank");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "WorkItemFilterFingerprint must be a lower-case SHA-256 value");
        }
    }
}
