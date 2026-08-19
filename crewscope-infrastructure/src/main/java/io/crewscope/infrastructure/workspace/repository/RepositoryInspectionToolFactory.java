package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.util.Objects;

/** Opens policy-bound RepositoryInspectionTool sessions over M4-I04 guarded Sandboxes. */
public final class RepositoryInspectionToolFactory {

    private final GitCommandExecutor gitCommands;
    private final int maximumPageSize;
    private final int maximumReadLines;
    private final int maximumTreeDepth;
    private final int maximumBackendOperations;
    private final int maximumPatternLength;
    private final int maximumResultBytes;

    RepositoryInspectionToolFactory(
            RepositoryInspectionProperties properties, GitCommandExecutor gitCommands) {
        RepositoryInspectionProperties configured = Objects.requireNonNull(properties, "properties");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
        this.maximumPageSize = configured.requiredMaxPageSize();
        this.maximumReadLines = configured.requiredMaxReadLines();
        this.maximumTreeDepth = configured.requiredMaxTreeDepth();
        this.maximumBackendOperations = configured.requiredMaxBackendOperations();
        this.maximumPatternLength = configured.requiredMaxPatternLength();
        this.maximumResultBytes = configured.requiredMaxResultBytes();
    }

    /** Opens the exclusive inspection window after matching every durable and physical fact. */
    public RepositoryInspectionSession open(
            ManagedTaskExecutionSandbox sandbox,
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            ManagedRepository repository,
            WorkspacePolicy policy,
            ExecutionLease lease,
            UtcTimestamp authoritativeNow) {
        ManagedTaskExecutionSandbox managedSandbox = Objects.requireNonNull(sandbox, "sandbox");
        ManagedWorktree managedWorktree = Objects.requireNonNull(worktree, "worktree");
        ManagedRepository managedRepository = Objects.requireNonNull(repository, "repository");
        WorkspacePolicy effectivePolicy = Objects.requireNonNull(policy, "policy");
        requireExactFacts(managedSandbox, managedWorktree, managedRepository, effectivePolicy);

        TaskExecutionSandboxCall call = managedSandbox.openCall(
                Objects.requireNonNull(workspace, "workspace"),
                Objects.requireNonNull(lease, "lease"),
                Objects.requireNonNull(authoritativeNow, "authoritativeNow"));
        try {
            SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
            filesystem.setSandbox(call.sandboxContext().getExternalSandbox());
            RepositoryInspectionPathGuard pathGuard = new RepositoryInspectionPathGuard(
                    managedWorktree.canonicalPath(),
                    managedSandbox.descriptor().repositoryContainerPath(),
                    effectivePolicy.allowedPaths(),
                    maximumPatternLength);
            int resultBytes = (int) Math.min(
                    maximumResultBytes,
                    Math.min(effectivePolicy.sandboxBudget().maxCommandOutputBytes(), Integer.MAX_VALUE));
            RepositoryInspectionTool tool = new RepositoryInspectionTool(
                    call,
                    filesystem,
                    gitCommands,
                    managedWorktree,
                    managedRepository,
                    pathGuard,
                    effectivePolicy.allowedPaths(),
                    maximumPageSize,
                    maximumReadLines,
                    maximumTreeDepth,
                    maximumBackendOperations,
                    resultBytes);
            return new RepositoryInspectionSession(call, filesystem, tool);
        } catch (RuntimeException failure) {
            call.close();
            throw failure;
        }
    }

    private static void requireExactFacts(
            ManagedTaskExecutionSandbox sandbox,
            ManagedWorktree worktree,
            ManagedRepository repository,
            WorkspacePolicy policy) {
        TaskExecutionSandboxDescriptor descriptor = sandbox.descriptor();
        if (!descriptor.worktree().workspaceId().equals(worktree.workspaceId())
                || !descriptor.worktree().physicalFingerprint().equals(worktree.physicalFingerprint())
                || !descriptor.policy().policyHash().equals(policy.policyHash())
                || !worktree.repositoryKey().equals(repository.repositoryKey())) {
            throw new RepositoryInspectionException(
                    RepositoryInspectionError.INVALID_CONTEXT,
                    "Repository inspection facts do not match the current Sandbox epoch");
        }
    }
}
