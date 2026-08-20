package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.WorkspaceWriteBudgetStore;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.infrastructure.persistence.coding.InMemoryWorkspaceWriteBudgetStore;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shares mutation usage across repeated sessions and same-process Sandbox recovery. */
final class CodingFilesystemUsageRegistry {

    private final ConcurrentMap<ExecutionWorkspaceKey, CodingFilesystemUsage> usages =
            new ConcurrentHashMap<>();
    private final WorkspaceWriteBudgetStore store;

    CodingFilesystemUsageRegistry() {
        this(new InMemoryWorkspaceWriteBudgetStore());
    }

    CodingFilesystemUsageRegistry(WorkspaceWriteBudgetStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    CodingFilesystemUsage acquire(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Set<String> initialChangedPaths,
            long initialWrittenBytes) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        return usages.compute(requiredWorkspace.workspaceKey(), (key, current) -> current == null
                ? new CodingFilesystemUsage(
                        requiredWorkspace,
                        requiredPolicy,
                        initialChangedPaths,
                        initialWrittenBytes,
                        store)
                : current.requireSame(requiredWorkspace, requiredPolicy));
    }

    /** Releases terminal Workspace usage; M4-A03 owns the live lifecycle callback. */
    void forget(ExecutionWorkspaceKey workspaceKey) {
        usages.remove(Objects.requireNonNull(workspaceKey, "workspaceKey"));
    }
}
