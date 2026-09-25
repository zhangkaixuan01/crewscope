package io.crewscope.application.principal;

import java.util.Locale;
import java.util.Objects;

/** Canonical SHA-256 fingerprint of one directory filter, bound into every continuation cursor. */
public record PrincipalDirectoryFilterFingerprint(String value) {

    private static final int HEX_LENGTH = 64;

    public PrincipalDirectoryFilterFingerprint {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{%d}".formatted(HEX_LENGTH))) {
            throw new IllegalArgumentException(
                    "PrincipalDirectoryFilterFingerprint must be 64 lowercase hex characters");
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
