package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.WorkspaceWriteBudgetExceededException;
import io.crewscope.application.coding.WorkspaceWriteBudgetSnapshot;
import io.crewscope.application.coding.WorkspaceWriteBudgetStore;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local fallback used by focused tests that do not start PostgreSQL. */
public final class InMemoryWorkspaceWriteBudgetStore implements WorkspaceWriteBudgetStore {

    private final ConcurrentMap<ExecutionWorkspaceId, WorkspaceWriteBudgetSnapshot> snapshots =
            new ConcurrentHashMap<>();

    @Override
    public WorkspaceWriteBudgetSnapshot initialize(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> changedPathsLowerBound,
            long writtenBytesLowerBound) {
        requireContext(workspace, policy);
        Set<String> lowerBound = Set.copyOf(changedPathsLowerBound);
        return snapshots.compute(workspace.id(), (ignored, current) -> {
            Set<String> paths = union(
                    current == null ? Set.of() : current.changedPaths(), lowerBound);
            int operations = Math.max(
                    current == null ? 0 : current.writeOperations(), paths.size());
            long bytes = Math.max(
                    current == null ? 0 : current.writtenBytes(), writtenBytesLowerBound);
            requireWithinBudget(policy, operations, bytes, paths.size());
            return new WorkspaceWriteBudgetSnapshot(
                    workspace.id(),
                    policy.policyHash(),
                    operations,
                    bytes,
                    paths,
                    current == null ? 0 : current.reservationSequence());
        });
    }

    @Override
    public WorkspaceWriteBudgetSnapshot reserve(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> changedPaths,
            long writtenBytes) {
        requireContext(workspace, policy);
        if (writtenBytes < 0) {
            throw new IllegalArgumentException("writtenBytes must not be negative");
        }
        return snapshots.compute(workspace.id(), (ignored, current) -> {
            if (current == null || !current.policyHash().equals(policy.policyHash())) {
                throw new IllegalStateException("Workspace write budget was not initialized");
            }
            Set<String> paths = union(current.changedPaths(), Set.copyOf(changedPaths));
            try {
                int operations = Math.addExact(current.writeOperations(), 1);
                long bytes = Math.addExact(current.writtenBytes(), writtenBytes);
                long sequence = Math.addExact(current.reservationSequence(), 1);
                requireWithinBudget(policy, operations, bytes, paths.size());
                return new WorkspaceWriteBudgetSnapshot(
                        workspace.id(), policy.policyHash(), operations, bytes, paths, sequence);
            } catch (ArithmeticException overflow) {
                throw new WorkspaceWriteBudgetExceededException();
            }
        });
    }

    private static void requireContext(ExecutionWorkspace workspace, WorkspacePolicy policy) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(policy, "policy");
        // This fallback is deliberately persistence-free; the PostgreSQL adapter owns complete
        // lineage and fencing validation in production.
    }

    private static void requireWithinBudget(
            WorkspacePolicy policy, int operations, long bytes, int files) {
        var budget = policy.operationBudget();
        if (operations > budget.maxWriteOperations()
                || bytes > budget.maxWrittenBytes()
                || files > budget.maxChangedFiles()) {
            throw new WorkspaceWriteBudgetExceededException();
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }
}
