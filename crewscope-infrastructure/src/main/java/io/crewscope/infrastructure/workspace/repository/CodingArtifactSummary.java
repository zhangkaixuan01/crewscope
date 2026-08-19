package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Objects;
import java.util.Optional;

/** Explicit public metadata whitelist with no content, path, producer or Tombstone detail. */
public record CodingArtifactSummary(
        ArtifactId artifactId,
        CodingArtifactKind kind,
        String contentType,
        long sizeBytes,
        RuntimeContentHash contentHash,
        CodingArtifactAvailability availability,
        Optional<UtcTimestamp> retentionUntil) {

    public CodingArtifactSummary {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(kind, "kind");
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(availability, "availability");
        retentionUntil = Objects.requireNonNull(retentionUntil, "retentionUntil");
    }
}
