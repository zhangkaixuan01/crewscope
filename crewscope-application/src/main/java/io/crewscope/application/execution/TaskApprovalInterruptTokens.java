package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.TaskExecutionId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Reconstructs the opaque approval coordinate without persisting its raw value. */
public final class TaskApprovalInterruptTokens {

    private TaskApprovalInterruptTokens() {}

    public static ExecutionInterruptToken from(
            TaskExecutionId executionId, AgentRunId runId, long segmentSequence) {
        TaskExecutionId execution = Objects.requireNonNull(executionId, "executionId");
        AgentRunId run = Objects.requireNonNull(runId, "runId");
        if (segmentSequence < 1) {
            throw new IllegalArgumentException("segmentSequence must be positive");
        }
        UUID coordinate = UUID.nameUUIDFromBytes(
                ("crewscope-task-approval|" + execution + "|" + run + "|" + segmentSequence)
                        .getBytes(StandardCharsets.UTF_8));
        return new ExecutionInterruptToken(coordinate.toString());
    }
}
