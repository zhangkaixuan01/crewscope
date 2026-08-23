package io.crewscope.domain.review;

import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.List;
import java.util.Objects;

/** Closed Diff authority used by ReviewSubject, ContextPackage and ReviewRequest. */
public record ReviewDiffReference(
        WorkItemScope scope,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        int attempt,
        DiffArtifactReference artifact,
        CodingTargetSnapshotReference codingTarget,
        RepositoryCommitId baselineCommit,
        RepositoryCommitId deliveryCommit,
        DiffGeneration generation,
        RuntimeContentHash manifestHash,
        PatchArtifactReference patchArtifact,
        List<DiffPath> changedPaths) {

    public ReviewDiffReference {
        scope = Objects.requireNonNull(scope, "scope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        artifact = Objects.requireNonNull(artifact, "artifact");
        codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
        deliveryCommit = Objects.requireNonNull(deliveryCommit, "deliveryCommit");
        generation = Objects.requireNonNull(generation, "generation");
        manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
        patchArtifact = Objects.requireNonNull(patchArtifact, "patchArtifact");
        changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        if (changedPaths.isEmpty()
                || changedPaths.stream().distinct().count() != changedPaths.size()) {
            throw new IllegalArgumentException("changedPaths must be non-empty and unique");
        }
        changedPaths = changedPaths.stream().sorted().toList();
    }

    public static ReviewDiffReference from(DiffArtifact artifact) {
        DiffArtifact required = Objects.requireNonNull(artifact, "artifact");
        return new ReviewDiffReference(
                required.scope(),
                required.taskId(),
                required.taskExecutionId(),
                required.attempt(),
                required.reference(),
                required.codingTarget(),
                required.baselineCommit(),
                required.deliveryCommit(),
                required.manifest().generation(),
                required.manifest().contentHash(),
                required.patchArtifact(),
                required.manifest().files().stream().map(file -> file.path()).toList());
    }
}
