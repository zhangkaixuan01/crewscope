package io.crewscope.domain.coding.event;

import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Terminal immutable Diff summary that closes live Workspace Diff observation. */
public record FinalDiffArtifactPublished(
        UUID diffArtifactId,
        UUID workspaceId,
        UUID taskExecutionId,
        int attempt,
        long diffGeneration,
        String manifestHash,
        long fileCount,
        long additions,
        long deletions,
        String finalHash) implements DomainEvent {

    public FinalDiffArtifactPublished {
        diffArtifactId = Objects.requireNonNull(diffArtifactId, "diffArtifactId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || diffGeneration < 1 || fileCount < 0 || additions < 0 || deletions < 0) {
            throw new IllegalArgumentException("Final Diff counters are invalid");
        }
        manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
        finalHash = Objects.requireNonNull(finalHash, "finalHash");
    }

    public static FinalDiffArtifactPublished from(DiffArtifact artifact) {
        DiffArtifact value = Objects.requireNonNull(artifact, "artifact");
        return new FinalDiffArtifactPublished(
                value.id().value(),
                value.executionWorkspaceId().value(),
                value.taskExecutionId().value(),
                value.attempt(),
                value.manifest().generation().value(),
                value.manifest().contentHash().value(),
                value.manifest().fileCount(),
                value.manifest().additions(),
                value.manifest().deletions(),
                value.finalHash().value());
    }
}
