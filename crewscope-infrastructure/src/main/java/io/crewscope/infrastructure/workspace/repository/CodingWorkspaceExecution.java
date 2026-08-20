package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceCompletionReason;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.Objects;
import java.util.Optional;

/** Worker-local physical handle backed by durable Workspace, Policy and fencing facts. */
public final class CodingWorkspaceExecution {

    private volatile ExecutionWorkspace workspace;
    private final CodingTargetSnapshot target;
    private final WorkspacePolicy policy;
    private final BuildProfile buildProfile;
    private final ManagedRepository repository;
    private final ManagedWorktree worktree;
    private final ManagedTaskExecutionSandbox sandbox;
    private volatile WorkspaceDiffMonitor diffMonitor;
    private volatile DiffArtifact finalDiff;
    private volatile DiffManifest lastLiveManifest;

    CodingWorkspaceExecution(
            ExecutionWorkspace workspace,
            CodingTargetSnapshot target,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            ManagedRepository repository,
            ManagedWorktree worktree,
            ManagedTaskExecutionSandbox sandbox) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.target = Objects.requireNonNull(target, "target");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.buildProfile = Objects.requireNonNull(buildProfile, "buildProfile");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.worktree = Objects.requireNonNull(worktree, "worktree");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    }

    public ExecutionWorkspace workspace() { return workspace; }
    public CodingTargetSnapshot target() { return target; }
    public WorkspacePolicy policy() { return policy; }
    public BuildProfile buildProfile() { return buildProfile; }
    public ManagedRepository repository() { return repository; }
    public ManagedWorktree worktree() { return worktree; }
    public ManagedTaskExecutionSandbox sandbox() { return sandbox; }
    public Optional<WorkspaceDiffMonitor> diffMonitor() { return Optional.ofNullable(diffMonitor); }
    public Optional<DiffArtifact> finalDiff() { return Optional.ofNullable(finalDiff); }
    public Optional<DiffManifest> lastLiveManifest() {
        return Optional.ofNullable(lastLiveManifest);
    }

    /** True after successful delivery is immutable and can no longer return to a paused Workspace. */
    public boolean hasSealedSuccessfulResult() {
        return finalDiff != null
                && workspace.status() == ExecutionWorkspaceStatus.FINALIZING
                && workspace.completionReason()
                        .filter(ExecutionWorkspaceCompletionReason.SUCCEEDED::equals)
                        .isPresent();
    }

    void workspace(ExecutionWorkspace value) {
        this.workspace = Objects.requireNonNull(value, "workspace");
    }

    void diffMonitor(WorkspaceDiffMonitor value) {
        this.diffMonitor = Objects.requireNonNull(value, "diffMonitor");
    }

    void finalDiff(DiffArtifact value) {
        this.finalDiff = Objects.requireNonNull(value, "finalDiff");
    }

    void closeMonitor() {
        WorkspaceDiffMonitor current = diffMonitor;
        diffMonitor = null;
        if (current != null) {
            lastLiveManifest = current.latest().orElse(lastLiveManifest);
            current.close();
        }
    }
}
