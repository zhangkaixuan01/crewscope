package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactDataClassification;
import io.crewscope.application.artifact.ArtifactVisibility;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Exact relational metadata expected around an ArtifactStore Descriptor. */
record CodingArtifactMetadata(
        ArtifactId artifactId,
        CodingArtifactKind kind,
        String contentType,
        long sizeBytes,
        RuntimeContentHash contentHash,
        WorkItemScope scope,
        TaskExecutionId taskExecutionId,
        Optional<PrincipalId> createdBy) {

    CodingArtifactMetadata {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentType, "contentType");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    static CodingArtifactMetadata patch(DiffArtifact artifact) {
        DiffArtifact value = Objects.requireNonNull(artifact, "artifact");
        var reference = value.patchArtifact();
        return new CodingArtifactMetadata(
                reference.artifactId(),
                CodingArtifactKind.PATCH,
                reference.contentType(),
                reference.sizeBytes(),
                reference.patchSha256(),
                value.scope(),
                value.taskExecutionId(),
                value.audit().createdBy());
    }

    static CodingArtifactMetadata commandLog(CommandEvidence evidence) {
        CommandEvidence value = Objects.requireNonNull(evidence, "evidence");
        return evidence(
                value.commandLog(),
                CodingArtifactKind.BUILD_LOG,
                value.scope(),
                value.taskExecutionId(),
                value.audit().createdBy());
    }

    static CodingArtifactMetadata testReport(TestEvidence evidence) {
        TestEvidence value = Objects.requireNonNull(evidence, "evidence");
        EvidenceArtifactReference reference = value.testReport().orElseThrow(() ->
                new CodingArtifactException(
                        CodingArtifactError.INVALID_CONTEXT,
                        "TestEvidence does not reference a test report"));
        return evidence(
                reference,
                CodingArtifactKind.TEST_REPORT,
                value.scope(),
                value.taskExecutionId(),
                value.audit().createdBy());
    }

    private static CodingArtifactMetadata evidence(
            EvidenceArtifactReference reference,
            CodingArtifactKind kind,
            WorkItemScope scope,
            TaskExecutionId executionId,
            Optional<PrincipalId> createdBy) {
        return new CodingArtifactMetadata(
                reference.artifactId(),
                kind,
                reference.contentType(),
                reference.sizeBytes(),
                reference.contentHash(),
                scope,
                executionId,
                createdBy);
    }

    void requireMatches(ArtifactDescriptor descriptor) {
        ArtifactDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
        ArtifactScope expectedScope = ArtifactScope.workspace(
                scope.organizationId(), Optional.of(scope.teamId()), scope.workspaceId());
        boolean actorMismatch = createdBy.isPresent()
                && !createdBy.orElseThrow().equals(value.producer().principalId());
        if (!artifactId.equals(value.artifactId())
                || !expectedScope.equals(value.scope())
                || !contentType.equals(value.contentType())
                || sizeBytes != value.size()
                || !contentHash.value().equals(value.sha256().toString())
                || value.dataClassification() != ArtifactDataClassification.RESTRICTED
                || value.visibility() != ArtifactVisibility.WORKSPACE
                || value.producer().taskExecutionId()
                        .filter(taskExecutionId.value()::equals)
                        .isEmpty()
                || actorMismatch) {
            throw new CodingArtifactException(
                    CodingArtifactError.METADATA_MISMATCH,
                    "ArtifactStore metadata does not match the committed Coding fact");
        }
    }
}
