package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceOwnership;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import java.util.Objects;

/** Central fail-closed lineage, policy and live ownership checks for M4-I04. */
final class TaskExecutionSandboxFacts {

    private TaskExecutionSandboxFacts() {}

    static void require(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            BuildProfile buildProfile,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        ManagedWorktree requiredWorktree = Objects.requireNonNull(worktree, "worktree");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        BuildProfile requiredProfile = Objects.requireNonNull(buildProfile, "buildProfile");
        if (!requiredWorkspace.id().equals(requiredWorktree.workspaceId())
                || !requiredWorkspace.repositoryKey().equals(requiredWorktree.repositoryKey())
                || !requiredWorkspace.workspaceKey().equals(requiredWorktree.workspaceKey())
                || !requiredWorkspace.managedBranch().equals(requiredWorktree.managedBranch())
                || !requiredWorkspace.baselineCommit().equals(requiredWorktree.baselineCommit())) {
            throw failure(
                    TaskExecutionSandboxError.WORKSPACE_MISMATCH,
                    "Managed Worktree does not match the durable Workspace identity");
        }
        if (!requiredWorkspace.scope().equals(requiredPolicy.scope())
                || !requiredWorkspace.taskId().equals(requiredPolicy.taskId())
                || !requiredWorkspace.taskExecutionId().equals(requiredPolicy.taskExecutionId())
                || requiredWorkspace.attempt() != requiredPolicy.attempt()
                || !requiredWorkspace.codingTarget().equals(requiredPolicy.codingTarget())
                || !requiredPolicy.buildProfile().equals(requiredProfile.reference())
                || requiredPolicy.sandboxBudget().networkMode() != SandboxNetworkMode.NONE
                || !requiredPolicy.sandboxBudget().readOnlyRootFilesystem()) {
            throw failure(
                    TaskExecutionSandboxError.POLICY_MISMATCH,
                    "Workspace, Policy and BuildProfile facts do not close");
        }
        requireOwnership(requiredWorkspace, lease, authoritativeNow);
    }

    static void requireOwnership(
            ExecutionWorkspace workspace,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        ExecutionLease requiredLease = Objects.requireNonNull(lease, "lease");
        ExecutionWorkspaceOwnership ownership = requiredWorkspace.ownership();
        if (!requiredWorkspace.taskExecutionId().equals(requiredLease.taskExecutionId())
                || requiredWorkspace.attempt() != requiredLease.attempt()
                || !ownership.environment().equals(requiredLease.environment())
                || !ownership.runtimeId().equals(requiredLease.runtimeId())
                || !ownership.workerId().equals(requiredLease.workerId())
                || !ownership.leaseId().equals(requiredLease.id())
                || !ownership.fencingToken().equals(requiredLease.fencingToken())) {
            throw failure(
                    TaskExecutionSandboxError.OWNERSHIP_MISMATCH,
                    "Sandbox access requires the Workspace current Lease and fencing epoch");
        }
        if (!requiredLease.isActiveAt(Objects.requireNonNull(authoritativeNow, "authoritativeNow"))) {
            throw failure(
                    TaskExecutionSandboxError.LEASE_EXPIRED,
                    "Sandbox access requires a current active Lease");
        }
    }

    static TaskExecutionSandboxException failure(
            TaskExecutionSandboxError error, String summary) {
        return new TaskExecutionSandboxException(error, summary);
    }
}
