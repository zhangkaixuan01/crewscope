package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactMutationContext;
import io.crewscope.application.artifact.ArtifactPurgeRequest;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactTombstone;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.shared.id.ArtifactId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Applies logical deletion only after scope and committed Coding metadata are revalidated. */
public final class CodingArtifactLifecycle {

    private final ArtifactStore artifactStore;

    CodingArtifactLifecycle(ArtifactStore artifactStore) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    }

    public ArtifactTombstone tombstonePatch(
            DiffArtifact artifact,
            ArtifactAccessContext accessContext,
            ArtifactMutationContext mutationContext,
            ArtifactTombstoneReason reason,
            Optional<String> detail) {
        return tombstone(
                CodingArtifactMetadata.patch(artifact),
                accessContext,
                mutationContext,
                reason,
                detail);
    }

    public ArtifactTombstone tombstoneBuildLog(
            CommandEvidence evidence,
            ArtifactAccessContext accessContext,
            ArtifactMutationContext mutationContext,
            ArtifactTombstoneReason reason,
            Optional<String> detail) {
        return tombstone(
                CodingArtifactMetadata.commandLog(evidence),
                accessContext,
                mutationContext,
                reason,
                detail);
    }

    public ArtifactTombstone tombstoneTestReport(
            TestEvidence evidence,
            ArtifactAccessContext accessContext,
            ArtifactMutationContext mutationContext,
            ArtifactTombstoneReason reason,
            Optional<String> detail) {
        return tombstone(
                CodingArtifactMetadata.testReport(evidence),
                accessContext,
                mutationContext,
                reason,
                detail);
    }

    /** Physical purge remains a bounded operator/sweeper action and returns exact audit IDs. */
    public List<ArtifactId> purge(ArtifactPurgeRequest request) {
        return artifactStore.purgeTombstoned(Objects.requireNonNull(request, "request"));
    }

    private ArtifactTombstone tombstone(
            CodingArtifactMetadata metadata,
            ArtifactAccessContext accessContext,
            ArtifactMutationContext mutationContext,
            ArtifactTombstoneReason reason,
            Optional<String> detail) {
        ArtifactAccessContext access = Objects.requireNonNull(accessContext, "accessContext");
        ArtifactMutationContext mutation = Objects.requireNonNull(mutationContext, "mutationContext");
        if (!metadata.scope().organizationId().equals(access.organizationId())
                || !access.organizationId().equals(mutation.organizationId())
                || !access.principalId().equals(mutation.principalId())) {
            throw new CodingArtifactException(
                    CodingArtifactError.INVALID_CONTEXT,
                    "Coding Artifact lifecycle context is outside the committed scope");
        }
        ArtifactDescriptor descriptor = artifactStore.head(metadata.artifactId(), access)
                .orElseThrow(() -> new CodingArtifactException(
                        CodingArtifactError.CONTENT_UNAVAILABLE,
                        "Coding Artifact is unavailable or unauthorized"));
        metadata.requireMatches(descriptor);
        try {
            return artifactStore
                    .tombstone(
                            metadata.artifactId(),
                            mutation,
                            Objects.requireNonNull(reason, "reason"),
                            Objects.requireNonNull(detail, "detail"))
                    .orElseThrow(() -> new CodingArtifactException(
                            CodingArtifactError.CONTENT_UNAVAILABLE,
                            "Coding Artifact disappeared before logical deletion"));
        } catch (CodingArtifactException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CodingArtifactException(
                    CodingArtifactError.LIFECYCLE_FAILED,
                    "Coding Artifact lifecycle mutation failed",
                    failure);
        }
    }
}
