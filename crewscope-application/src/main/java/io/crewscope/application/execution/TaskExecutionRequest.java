package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/** Trusted request that starts the current durable AgentRun Segment for a Task execution. */
public record TaskExecutionRequest(
        TaskExecutionRuntimeFacts facts,
        UUID correlationId) {

    public TaskExecutionRequest {
        facts = Objects.requireNonNull(facts, "facts");
        if (!facts.runtimeSession().canInvoke()) {
            throw new IllegalArgumentException("runtimeSession must be ACTIVE for Task execution");
        }
        correlationId = requireId(correlationId, "correlationId");
    }

    private static UUID requireId(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }

    @Override
    public String toString() {
        return "TaskExecutionRequest[taskExecutionId=" + facts.execution().id()
                + ", agentRunId=" + facts.agentRun().id()
                + ", segment=" + facts.agentRun().currentSegment().sequence()
                + ", correlationId=" + correlationId
                + ", facts=[REDACTED]]";
    }
}
