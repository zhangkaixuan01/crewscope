package io.crewscope.application.execution;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/** One ordered event identified by durable TaskExecution, AgentRun and Segment coordinates. */
public record TaskExecutionEvent(
        TaskExecutionId taskExecutionId,
        int attempt,
        AgentRunId agentRunId,
        long segmentSequence,
        long sequence,
        UtcTimestamp occurredAt,
        TaskExecutionEventPayload payload) {

    public TaskExecutionEvent {
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        if (segmentSequence < 1 || sequence < 1) {
            throw new IllegalArgumentException("segmentSequence and sequence must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Objects.requireNonNull(payload, "payload");
    }

    public boolean terminal() {
        return payload.terminalStatus().isPresent();
    }
}
