package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Objects;

/** Integrity-closed logical ArtifactStore reference for a complete Git Patch. */
public record PatchArtifactReference(
        ArtifactId artifactId, long sizeBytes, RuntimeContentHash patchSha256) {

    public static final String CONTENT_TYPE = "text/x-diff;charset=utf-8";

    public PatchArtifactReference {
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        if (sizeBytes < 0) {
            throw new DomainValidationException(
                    "patchArtifactReference.sizeBytes", "must not be negative");
        }
        patchSha256 = Objects.requireNonNull(patchSha256, "patchSha256");
        if (sizeBytes == 0 && !RuntimeContentHash.sha256("").equals(patchSha256)) {
            throw new DomainValidationException(
                    "patchArtifactReference.patchSha256",
                    "an empty Patch must use the SHA-256 of empty content");
        }
    }

    public String contentType() {
        return CONTENT_TYPE;
    }
}
