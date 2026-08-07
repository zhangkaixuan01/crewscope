package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.ArtifactId;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** Streaming storage Port for immutable content and independently governed logical references. */
public interface ArtifactStore {

    /**
     * Validates declared size and SHA-256 before atomically publishing the logical Artifact.
     *
     * <p>The caller owns and closes the input stream. A retry with an identical ID and request is
     * idempotent; different content or metadata for the same ID raises {@link
     * ArtifactStoreError#CONFLICT}.
     */
    ArtifactDescriptor put(ArtifactWriteRequest request, InputStream content);

    /** Returns authorized lifecycle metadata, including an existing Tombstone. */
    Optional<ArtifactDescriptor> head(ArtifactId artifactId, ArtifactAccessContext accessContext);

    /**
     * Returns content only when the Artifact is authorized, active and within retention.
     *
     * <p>The adapter verifies the stored size and SHA-256 against the Descriptor and reports a
     * mismatch as {@link ArtifactStoreError#INTEGRITY_VIOLATION}.
     */
    Optional<ArtifactContent> get(ArtifactId artifactId, ArtifactAccessContext accessContext);

    /**
     * Creates a logical deletion fact after application policy authorizes the mutation.
     *
     * <p>An identical reason and normalized detail return the existing Tombstone. A different
     * request for an already tombstoned Artifact raises {@link ArtifactStoreError#CONFLICT}.
     */
    Optional<ArtifactTombstone> tombstone(
            ArtifactId artifactId,
            ArtifactMutationContext mutationContext,
            ArtifactTombstoneReason reason,
            Optional<String> detail);

    /**
     * Physically removes at most {@code batchSize} purge-eligible logical references and content.
     *
     * <p>Implementations return the exact IDs removed so the caller can emit AuditEvent records
     * and reconcile retained references.
     */
    List<ArtifactId> purgeTombstoned(ArtifactPurgeRequest request);
}
