package io.crewscope.application.operations;

import java.util.Objects;

/** SHA-256 over the semantic recovery request, excluding presentation-only confirmation text. */
public record OperationsRecoveryFingerprint(String value) {

    public OperationsRecoveryFingerprint {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("recovery fingerprint must be a lowercase SHA-256");
        }
    }
}
