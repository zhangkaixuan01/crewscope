package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.TaskExecutionStatus;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

/** Idempotent Pause, Resume or Cancel request for the current execution owner. */
public record TaskExecutionControlRequest(
        TaskExecutionRuntimeFacts facts,
        TaskExecutionControlAction action,
        UUID controlRequestId,
        String reason,
        UUID correlationId) {

    private static final EnumSet<TaskExecutionStatus> PAUSABLE = EnumSet.of(
            TaskExecutionStatus.RUNNING, TaskExecutionStatus.PAUSE_REQUESTED);
    private static final EnumSet<TaskExecutionStatus> CANCELABLE = EnumSet.of(
            TaskExecutionStatus.CLAIMED,
            TaskExecutionStatus.PREPARING,
            TaskExecutionStatus.RUNNING,
            TaskExecutionStatus.PAUSE_REQUESTED,
            TaskExecutionStatus.CANCEL_REQUESTED);

    public TaskExecutionControlRequest {
        facts = Objects.requireNonNull(facts, "facts");
        action = Objects.requireNonNull(action, "action");
        controlRequestId = requireId(controlRequestId, "controlRequestId");
        reason = requireReason(reason);
        correlationId = requireId(correlationId, "correlationId");
        requireActionState(facts, action);
    }

    private static void requireActionState(
            TaskExecutionRuntimeFacts facts, TaskExecutionControlAction action) {
        TaskExecutionStatus status = facts.execution().status();
        boolean supported = switch (action) {
            case PAUSE -> PAUSABLE.contains(status);
            case CANCEL -> CANCELABLE.contains(status);
            case RESUME -> status == TaskExecutionStatus.RUNNING
                    && facts.runtimeSession().canInvoke()
                    && facts.agentRun().currentSegment().kind() != AgentRunSegmentKind.INVOKE;
        };
        if (!supported) {
            throw new IllegalArgumentException(
                    "control action is incompatible with the current TaskExecution and Segment");
        }
    }

    private static UUID requireId(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }

    private static String requireReason(String value) {
        String required = Objects.requireNonNull(value, "reason").strip();
        if (required.isEmpty()
                || required.length() > 500
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "reason must contain 1 to 500 printable characters");
        }
        return required;
    }

    @Override
    public String toString() {
        return "TaskExecutionControlRequest[taskExecutionId=" + facts.execution().id()
                + ", agentRunId=" + facts.agentRun().id()
                + ", action=" + action
                + ", controlRequestId=" + controlRequestId
                + ", correlationId=" + correlationId
                + ", facts=[REDACTED]]";
    }
}
