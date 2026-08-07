package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Immutable metadata returned for one logical Artifact reference. */
public record ArtifactDescriptor(
        ArtifactId artifactId,
        ArtifactScope scope,
        String contentType,
        long size,
        Sha256Hash sha256,
        ArtifactDataClassification dataClassification,
        ArtifactVisibility visibility,
        URI storageUri,
        ArtifactEncryption encryption,
        ArtifactProducer producer,
        UtcTimestamp createdAt,
        Optional<UtcTimestamp> retentionUntil,
        Optional<ArtifactTombstone> tombstone) {

    public ArtifactDescriptor {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(scope, "scope");
        contentType = ArtifactWriteRequest.requireContentType(contentType);
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(dataClassification, "dataClassification");
        Objects.requireNonNull(visibility, "visibility");
        scope.validateVisibility(visibility);
        storageUri = requireStorageUri(storageUri);
        Objects.requireNonNull(encryption, "encryption");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(createdAt, "createdAt");
        retentionUntil = Objects.requireNonNull(retentionUntil, "retentionUntil");
        retentionUntil.ifPresent(deadline -> {
            if (deadline.compareTo(createdAt) <= 0) {
                throw new IllegalArgumentException("retentionUntil must be after createdAt");
            }
        });
        tombstone = Objects.requireNonNull(tombstone, "tombstone");
        tombstone.ifPresent(value -> {
            if (value.tombstonedAt().compareTo(createdAt) < 0) {
                throw new IllegalArgumentException("tombstonedAt must not be before createdAt");
            }
        });
    }

    /** Returns true at and after the retention deadline. */
    public boolean isExpiredAt(UtcTimestamp timestamp) {
        UtcTimestamp now = Objects.requireNonNull(timestamp, "timestamp");
        return retentionUntil.map(deadline -> deadline.compareTo(now) <= 0).orElse(false);
    }

    /** Content remains available only while the object is active and within retention. */
    public boolean isContentAvailableAt(UtcTimestamp timestamp) {
        return tombstone.isEmpty() && !isExpiredAt(timestamp);
    }

    /** A Tombstone and completed retention period are both required before physical cleanup. */
    public boolean isPurgeEligibleAt(UtcTimestamp timestamp) {
        UtcTimestamp now = Objects.requireNonNull(timestamp, "timestamp");
        if (tombstone.isEmpty() || tombstone.orElseThrow().tombstonedAt().compareTo(now) > 0) {
            return false;
        }
        return retentionUntil.map(deadline -> deadline.compareTo(now) <= 0).orElse(true);
    }

    /** Compares an idempotent write request with this persisted logical reference. */
    public boolean matches(ArtifactWriteRequest request) {
        ArtifactWriteRequest candidate = Objects.requireNonNull(request, "request");
        return artifactId.equals(candidate.artifactId())
                && scope.equals(candidate.scope())
                && contentType.equals(candidate.contentType())
                && size == candidate.declaredSize()
                && sha256.equals(candidate.expectedHash())
                && dataClassification == candidate.dataClassification()
                && visibility == candidate.visibility()
                && producer.equals(candidate.producer())
                && retentionUntil.equals(candidate.retentionUntil(createdAt));
    }

    private static URI requireStorageUri(URI value) {
        URI uri = Objects.requireNonNull(value, "storageUri");
        if (!uri.isAbsolute() || uri.getScheme().isBlank()) {
            throw new IllegalArgumentException("storageUri must be absolute");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "storageUri must not contain user information, a query or a fragment");
        }
        return uri;
    }
}
