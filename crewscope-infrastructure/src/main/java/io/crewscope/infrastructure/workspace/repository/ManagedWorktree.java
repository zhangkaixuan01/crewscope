package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import java.nio.file.Path;
import java.util.Objects;

/** Verified managed Worktree; its canonical host location stays package-internal. */
public final class ManagedWorktree {

    private final ExecutionWorkspaceId workspaceId;
    private final RepositoryKey repositoryKey;
    private final ExecutionWorkspaceKey workspaceKey;
    private final ManagedWorkspaceBranch managedBranch;
    private final RepositoryCommitId baselineCommit;
    private final RepositoryCommitId headCommit;
    private final WorkspacePhysicalFingerprint physicalFingerprint;
    private final Path canonicalPath;

    ManagedWorktree(
            ExecutionWorkspaceId workspaceId,
            RepositoryKey repositoryKey,
            ExecutionWorkspaceKey workspaceKey,
            ManagedWorkspaceBranch managedBranch,
            RepositoryCommitId baselineCommit,
            RepositoryCommitId headCommit,
            WorkspacePhysicalFingerprint physicalFingerprint,
            Path canonicalPath) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        this.workspaceKey = Objects.requireNonNull(workspaceKey, "workspaceKey");
        this.managedBranch = Objects.requireNonNull(managedBranch, "managedBranch");
        this.baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
        this.headCommit = Objects.requireNonNull(headCommit, "headCommit");
        this.physicalFingerprint = Objects.requireNonNull(
                physicalFingerprint, "physicalFingerprint");
        this.canonicalPath = Objects.requireNonNull(canonicalPath, "canonicalPath");
    }

    public ExecutionWorkspaceId workspaceId() {
        return workspaceId;
    }

    public RepositoryKey repositoryKey() {
        return repositoryKey;
    }

    public ExecutionWorkspaceKey workspaceKey() {
        return workspaceKey;
    }

    public ManagedWorkspaceBranch managedBranch() {
        return managedBranch;
    }

    public RepositoryCommitId baselineCommit() {
        return baselineCommit;
    }

    public RepositoryCommitId headCommit() {
        return headCommit;
    }

    public WorkspacePhysicalFingerprint physicalFingerprint() {
        return physicalFingerprint;
    }

    Path canonicalPath() {
        return canonicalPath;
    }

    @Override
    public String toString() {
        return "ManagedWorktree[workspaceId=" + workspaceId
                + ", repositoryKey=" + repositoryKey.value()
                + ", workspaceKey=" + workspaceKey.value()
                + ", physicalFingerprint=" + physicalFingerprint.value() + "]";
    }
}
