package io.crewscope.domain.task.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Public fact that a pending AgentRun interruption opened its next RESUME Segment. */
public record AgentRunResumed(
        UUID taskExecutionId,
        UUID agentRunId,
        UUID agentInterruptId,
        long resumedSegmentSequence,
        UUID resumeRequestId) implements DomainEvent {

    public AgentRunResumed {
        taskExecutionId = AggregateId.requireValue(taskExecutionId, "taskExecutionId");
        agentRunId = AggregateId.requireValue(agentRunId, "agentRunId");
        agentInterruptId = AggregateId.requireValue(agentInterruptId, "agentInterruptId");
        if (resumedSegmentSequence < 2) {
            throw new IllegalArgumentException("resumedSegmentSequence must be at least two");
        }
        resumeRequestId = AggregateId.requireValue(resumeRequestId, "resumeRequestId");
    }
}
