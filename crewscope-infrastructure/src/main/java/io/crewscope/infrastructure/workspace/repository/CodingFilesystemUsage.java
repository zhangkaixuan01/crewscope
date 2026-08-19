package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Worker-held conservative write usage for one Workspace, shared by all live tool sessions. */
final class CodingFilesystemUsage {

    private final io.crewscope.domain.coding.ExecutionWorkspaceId workspaceId;
    private final io.crewscope.domain.task.TaskExecutionId taskExecutionId;
    private final int attempt;
    private final io.crewscope.domain.task.TaskFactHash policyHash;
    private final WorkspaceOperationBudget budget;
    private final Set<String> changedPaths = new LinkedHashSet<>();
    private int writeOperations;
    private long writtenBytes;

    CodingFilesystemUsage(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> initialChangedPaths,
            long initialWrittenBytes) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        this.workspaceId = requiredWorkspace.id();
        this.taskExecutionId = requiredWorkspace.taskExecutionId();
        this.attempt = requiredWorkspace.attempt();
        this.policyHash = requiredPolicy.policyHash();
        this.budget = requiredPolicy.operationBudget();
        this.changedPaths.addAll(Set.copyOf(initialChangedPaths));
        this.writeOperations = this.changedPaths.size();
        this.writtenBytes = initialWrittenBytes;
        requireWithinBudget(this.writeOperations, this.writtenBytes, this.changedPaths.size());
    }

    synchronized CodingFilesystemUsage requireSame(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        if (!workspaceId.equals(workspace.id())
                || !taskExecutionId.equals(workspace.taskExecutionId())
                || attempt != workspace.attempt()
                || !policyHash.equals(policy.policyHash())) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.INVALID_CONTEXT,
                    "Coding filesystem usage belongs to a different Workspace or Policy");
        }
        return this;
    }

    synchronized UsageSnapshot reserve(Set<String> paths, long bytes) {
        Set<String> nextPaths = new LinkedHashSet<>(changedPaths);
        nextPaths.addAll(Set.copyOf(paths));
        int nextOperations;
        long nextBytes;
        try {
            nextOperations = Math.addExact(writeOperations, 1);
            nextBytes = Math.addExact(writtenBytes, bytes);
        } catch (ArithmeticException overflow) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BUDGET_EXCEEDED,
                    "Coding filesystem mutation exceeds the Workspace operation budget",
                    overflow);
        }
        requireWithinBudget(nextOperations, nextBytes, nextPaths.size());
        changedPaths.clear();
        changedPaths.addAll(nextPaths);
        writeOperations = nextOperations;
        writtenBytes = nextBytes;
        return snapshot();
    }

    synchronized UsageSnapshot snapshot() {
        return new UsageSnapshot(writeOperations, writtenBytes, changedPaths.size());
    }

    private void requireWithinBudget(int operations, long bytes, int files) {
        if (operations > budget.maxWriteOperations()
                || bytes > budget.maxWrittenBytes()
                || files > budget.maxChangedFiles()) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BUDGET_EXCEEDED,
                    "Coding filesystem mutation exceeds the Workspace operation budget");
        }
    }

    record UsageSnapshot(int writeOperations, long writtenBytes, int changedFiles) {}
}
