package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** SHA-256 proof closing one logical Workspace against its current host resources. */
public record WorkspacePhysicalFingerprint(String value) {

    public WorkspacePhysicalFingerprint {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Workspace physical fingerprint must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
