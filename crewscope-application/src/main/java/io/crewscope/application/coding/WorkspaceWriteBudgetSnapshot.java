package io.crewscope.application.coding;

import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Set;

/** Durable conservative counters reserved before controlled filesystem mutations. */
public record WorkspaceWriteBudgetSnapshot(
        ExecutionWorkspaceId workspaceId,
        TaskFactHash policyHash,
        int writeOperations,
        long writtenBytes,
        Set<String> changedPaths,
        long reservationSequence) {

    public WorkspaceWriteBudgetSnapshot {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        policyHash = Objects.requireNonNull(policyHash, "policyHash");
        changedPaths = Set.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
        if (writeOperations < 0
                || writtenBytes < 0
                || reservationSequence < 0
                || changedPaths.size() > writeOperations) {
            throw new IllegalArgumentException("Workspace write budget counters are invalid");
        }
    }
}
