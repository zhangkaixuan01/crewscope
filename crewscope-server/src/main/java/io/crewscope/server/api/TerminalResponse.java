package io.crewscope.server.api;

import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionTerminal;
import java.time.Instant;
import java.util.UUID;

/** The terminal verdict of an execution, with its failure classification when one exists. */
public record TerminalResponse(
        String status,
        UUID decidedByPrincipalId,
        Instant decidedAt,
        String failureClass,
        String failureCode) {
    static TerminalResponse from(TaskExecutionTerminal value) {
        TaskExecutionFailure failure = value.failure().orElse(null);
        return new TerminalResponse(
                value.status().name(),
                value.decidedByPrincipalId().value(),
                value.decidedAt().value(),
                failure == null ? null : failure.failureClass().name(),
                failure == null ? null : failure.code());
    }
}
