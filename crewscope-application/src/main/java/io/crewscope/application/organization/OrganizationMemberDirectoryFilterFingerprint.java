package io.crewscope.application.organization;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical SHA-256 fingerprint of one organization member directory filter, bound into every
 * continuation cursor so a page only continues the screen that produced it.
 */
public record OrganizationMemberDirectoryFilterFingerprint(String value) {

    private static final int HEX_LENGTH = 64;

    public OrganizationMemberDirectoryFilterFingerprint {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{%d}".formatted(HEX_LENGTH))) {
            throw new IllegalArgumentException(
                    "OrganizationMemberDirectoryFilterFingerprint must be 64 lowercase hex characters");
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
