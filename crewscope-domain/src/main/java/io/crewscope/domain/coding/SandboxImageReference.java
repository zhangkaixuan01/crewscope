package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Immutable OCI image reference pinned by a lowercase SHA-256 digest. */
public record SandboxImageReference(String value) {

    public SandboxImageReference {
        if (value == null || !value.matches("[^\\s@]+@sha256:[0-9a-f]{64}")) {
            throw new DomainValidationException(
                    "buildProfile.sandboxImage", "must be an OCI image pinned by sha256 digest");
        }
    }
}
