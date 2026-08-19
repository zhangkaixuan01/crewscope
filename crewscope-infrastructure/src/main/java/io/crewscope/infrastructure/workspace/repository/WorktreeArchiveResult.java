package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.WorkspaceArchiveReference;
import io.crewscope.infrastructure.workspace.git.GitTreeId;
import java.util.Objects;

/** Immutable local delivery evidence returned after Worktree resources are archived. */
public record WorktreeArchiveResult(
        ExecutionWorkspaceId workspaceId,
        WorkspaceArchiveReference archiveReference,
        RepositoryCommitId deliveryCommit,
        GitTreeId deliveryTree) {

    public WorktreeArchiveResult {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        archiveReference = Objects.requireNonNull(archiveReference, "archiveReference");
        deliveryCommit = Objects.requireNonNull(deliveryCommit, "deliveryCommit");
        deliveryTree = Objects.requireNonNull(deliveryTree, "deliveryTree");
    }
}
