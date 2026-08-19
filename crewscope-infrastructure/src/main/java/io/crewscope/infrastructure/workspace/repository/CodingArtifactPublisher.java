package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDataClassification;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactProducer;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactVisibility;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.application.artifact.Sha256Hash;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.ArtifactId;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.Optional;

/** Applies one immutable publication, scope, sensitivity and retention policy to Coding bytes. */
final class CodingArtifactPublisher {

    private final ArtifactStore artifactStore;
    private final CodingArtifactProperties properties;

    CodingArtifactPublisher(ArtifactStore artifactStore, CodingArtifactProperties properties) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.properties.validate();
    }

    ArtifactDescriptor publish(
            ArtifactId artifactId,
            ExecutionWorkspace workspace,
            Principal actor,
            String contentType,
            byte[] content) {
        ExecutionWorkspace current = Objects.requireNonNull(workspace, "workspace");
        Principal principal = Objects.requireNonNull(actor, "actor");
        boolean outsideTeam = principal.scope().teamId().isPresent()
                && principal.scope().teamId().filter(current.scope().teamId()::equals).isEmpty();
        if (!principal.canAct()
                || !principal.scope().organizationId().equals(current.scope().organizationId())
                || outsideTeam) {
            throw new CodingArtifactException(
                    CodingArtifactError.INVALID_CONTEXT,
                    "Coding Artifact producer is outside the Workspace scope");
        }
        byte[] bytes = Objects.requireNonNull(content, "content").clone();
        if (bytes.length > properties.getMaximumArtifactBytes()) {
            throw new CodingArtifactException(
                    CodingArtifactError.SIZE_LIMIT_EXCEEDED,
                    "Coding Artifact exceeds the configured size limit");
        }
        ArtifactWriteRequest request = new ArtifactWriteRequest(
                Objects.requireNonNull(artifactId, "artifactId"),
                scope(current),
                contentType,
                bytes.length,
                Sha256Hash.digest(bytes),
                ArtifactDataClassification.RESTRICTED,
                ArtifactVisibility.WORKSPACE,
                Optional.of(properties.getRetention()),
                new ArtifactProducer(
                        principal.id(),
                        Optional.of(current.taskExecutionId().value()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        ArtifactDescriptor descriptor = artifactStore.put(
                request, new ByteArrayInputStream(bytes));
        if (descriptor == null || !descriptor.matches(request)) {
            throw new CodingArtifactException(
                    CodingArtifactError.PUBLICATION_FAILED,
                    "ArtifactStore returned inconsistent Coding Artifact metadata");
        }
        return descriptor;
    }

    static ArtifactScope scope(ExecutionWorkspace workspace) {
        ExecutionWorkspace value = Objects.requireNonNull(workspace, "workspace");
        return ArtifactScope.workspace(
                value.scope().organizationId(),
                Optional.of(value.scope().teamId()),
                value.scope().workspaceId());
    }
}
