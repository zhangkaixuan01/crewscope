package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Canonical SHA-256 closing the logical Workspace identity and current ownership generation. */
public record ExecutionWorkspaceFingerprint(String value) {

    public ExecutionWorkspaceFingerprint {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new DomainValidationException(
                    "executionWorkspace.fingerprint", "must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
