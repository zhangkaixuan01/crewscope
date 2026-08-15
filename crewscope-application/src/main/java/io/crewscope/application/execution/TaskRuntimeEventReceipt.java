package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Objects;
import java.util.UUID;

/** Immutable receipt proving that one exact AgentRun Segment event was committed. */
public record TaskRuntimeEventReceipt(
        OrganizationId organizationId,
        AgentRunId agentRunId,
        long segmentSequence,
        long eventSequence,
        RuntimeContentHash eventHash,
        String runtimeEventType,
        UUID domainEventId,
        UtcTimestamp runtimeOccurredAt,
        UtcTimestamp recordedAt) {

    public TaskRuntimeEventReceipt {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        if (segmentSequence < 1 || eventSequence < 1) {
            throw new IllegalArgumentException("segmentSequence and eventSequence must be positive");
        }
        eventHash = Objects.requireNonNull(eventHash, "eventHash");
        runtimeEventType = Objects.requireNonNull(runtimeEventType, "runtimeEventType").strip();
        if (!runtimeEventType.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException("runtimeEventType must be stable upper snake case");
        }
        domainEventId = Objects.requireNonNull(domainEventId, "domainEventId");
        runtimeOccurredAt = Objects.requireNonNull(runtimeOccurredAt, "runtimeOccurredAt");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
