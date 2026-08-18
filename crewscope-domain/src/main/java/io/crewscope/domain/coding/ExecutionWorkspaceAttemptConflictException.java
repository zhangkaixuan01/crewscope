package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Map;
import java.util.Objects;

/** Reports a second Workspace allocation for an already-bound TaskExecution attempt. */
public final class ExecutionWorkspaceAttemptConflictException extends DomainException {

    public ExecutionWorkspaceAttemptConflictException(
            TaskExecutionId taskExecutionId, int attempt) {
        super(new DomainError(
                DomainErrorCode.EXECUTION_WORKSPACE_ATTEMPT_CONFLICT,
                "ExecutionWorkspace already exists for this TaskExecution attempt",
                Map.of(
                        "taskExecutionId",
                        Objects.requireNonNull(taskExecutionId, "taskExecutionId").toString(),
                        "attempt",
                        Integer.toString(requireAttempt(attempt)))));
    }

    private static int requireAttempt(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        return value;
    }
}
