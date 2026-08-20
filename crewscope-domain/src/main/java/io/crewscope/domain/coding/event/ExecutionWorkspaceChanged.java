package io.crewscope.domain.coding.event;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Public lifecycle fact for one managed Coding Workspace. */
public record ExecutionWorkspaceChanged(
        UUID workspaceId,
        UUID taskExecutionId,
        int attempt,
        String status,
        Optional<String> recoveryTargetStatus,
        long recoveryGeneration,
        Optional<String> completionReason,
        Optional<String> failureCode,
        long workspaceVersion) implements DomainEvent {

    public ExecutionWorkspaceChanged {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || recoveryGeneration < 0 || workspaceVersion < 0) {
            throw new IllegalArgumentException("Workspace event counters are invalid");
        }
        status = Objects.requireNonNull(status, "status");
        recoveryTargetStatus = Objects.requireNonNull(
                recoveryTargetStatus, "recoveryTargetStatus");
        completionReason = Objects.requireNonNull(completionReason, "completionReason");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
    }

    public static ExecutionWorkspaceChanged from(ExecutionWorkspace workspace) {
        ExecutionWorkspace value = Objects.requireNonNull(workspace, "workspace");
        return new ExecutionWorkspaceChanged(
                value.id().value(),
                value.taskExecutionId().value(),
                value.attempt(),
                value.status().name(),
                value.recoveryTargetStatus().map(Enum::name),
                value.recoveryGeneration(),
                value.completionReason().map(Enum::name),
                value.failure().map(failure -> failure.code()),
                value.version());
    }
}
