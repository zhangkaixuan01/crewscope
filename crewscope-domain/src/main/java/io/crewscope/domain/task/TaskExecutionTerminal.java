package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Immutable terminal outcome kept separately from the operational state machine. */
public record TaskExecutionTerminal(
        TaskExecutionStatus status,
        PrincipalId decidedByPrincipalId,
        UtcTimestamp decidedAt,
        Optional<TaskExecutionFailure> failure) {

    public TaskExecutionTerminal {
        status = Objects.requireNonNull(status, "status");
        decidedByPrincipalId = Objects.requireNonNull(
                decidedByPrincipalId, "decidedByPrincipalId");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        failure = Objects.requireNonNull(failure, "failure");
        if (!status.isTerminal()) {
            throw new DomainValidationException(
                    "taskExecution.terminal.status", "must be a terminal status");
        }
        if ((status == TaskExecutionStatus.FAILED) != failure.isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.terminal.failure",
                    status == TaskExecutionStatus.FAILED
                            ? "is required for FAILED"
                            : "is only allowed for FAILED");
        }
    }
}
