package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shares command budgets and evidence order across repeated sessions in one Worker process. */
final class SandboxCommandUsageRegistry {

    private final ConcurrentMap<ExecutionWorkspaceKey, SandboxCommandUsage> usages =
            new ConcurrentHashMap<>();

    SandboxCommandUsage acquire(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            List<CommandEvidence> existingEvidence) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        List<CommandEvidence> existing = List.copyOf(existingEvidence);
        return usages.compute(requiredWorkspace.workspaceKey(), (key, current) -> current == null
                ? new SandboxCommandUsage(requiredWorkspace, requiredPolicy, existing)
                : current.requireSame(requiredWorkspace, requiredPolicy));
    }

    /** Releases terminal Workspace usage; M4-A03 owns the live lifecycle callback. */
    void forget(ExecutionWorkspaceKey workspaceKey) {
        usages.remove(Objects.requireNonNull(workspaceKey, "workspaceKey"));
    }
}
