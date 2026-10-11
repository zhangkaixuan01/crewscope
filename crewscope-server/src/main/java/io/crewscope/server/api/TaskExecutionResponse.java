package io.crewscope.server.api;

import io.crewscope.application.task.TaskAttempt;
import io.crewscope.application.task.TaskControlAction;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskExecutionResponse(
        UUID id,
        int attempt,
        int maxAttempts,
        UUID parentExecutionId,
        int priority,
        Instant notBefore,
        String status,
        WaitingResponse waiting,
        ControlRequestResponse controlRequest,
        TerminalResponse terminal,
        UUID executorPrincipalId,
        UUID currentPlanVersionId,
        long version,
        AuditResponse audit,
        List<AvailableActionResponse> availableActions) {
    /**
     * One attempt, with the control actions the server adjudicated for the requesting member.
     *
     * <p>The list is always complete — a disabled command keeps its entry and its reason — because
     * a control bar that renders only live buttons cannot explain the ones it hides. The element
     * type is shared with every other action-bearing surface.
     */
    static TaskExecutionResponse from(TaskAttempt attempt) {
        return from(attempt.execution(), attempt.availableActions());
    }

    /**
     * The same attempt without an adjudication, for responses that do not ask for one.
     *
     * <p>Runtime facts is a diagnostic read of the runtime aggregates, not a control surface, and
     * it does not resolve the member's responsibilities. {@code null} says so, rather than an
     * empty list that would claim every command was refused.
     */
    static TaskExecutionResponse withoutAvailability(TaskExecution value) {
        return from(value, null);
    }

    static TaskExecutionResponse from(
            TaskExecution value, List<TaskControlAction> availableActions) {
        return new TaskExecutionResponse(
                value.id().value(),
                value.attempt(),
                value.maxAttempts(),
                value.parentExecutionId().map(TaskExecutionId::value).orElse(null),
                value.priority().value(),
                value.notBefore().value(),
                value.status().name(),
                value.waiting().map(WaitingResponse::from).orElse(null),
                value.controlRequest().map(ControlRequestResponse::from).orElse(null),
                value.terminal().map(TerminalResponse::from).orElse(null),
                value.planningContext()
                        .map(context -> context.executionPrincipal().principalId().value())
                        .orElse(null),
                value.planningContext()
                        .flatMap(context -> context.currentPlanVersionId())
                        .map(id -> id.value())
                        .orElse(null),
                value.version(),
                AuditResponse.from(value.audit()),
                availableActions == null
                        ? null
                        : availableActions.stream()
                                .map(TaskExecutionResponse::response)
                                .toList());
    }

    /** Maps one adjudicated Task control command onto the shared wire shape. */
    private static AvailableActionResponse response(TaskControlAction value) {
        return AvailableActionResponse.of(
                value.actionId(),
                value.targetStatus().name(),
                value.label(),
                value.strength().name(),
                value.reversible(),
                value.enabled(),
                value.reason().orElse(null),
                value.remedy().orElse(null));
    }
}
