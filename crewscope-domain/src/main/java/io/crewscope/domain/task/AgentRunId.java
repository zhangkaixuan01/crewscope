package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one durable Agent run. */
public record AgentRunId(UUID value) implements AggregateId {
    public AgentRunId {
        value = AggregateId.requireValue(value, "AgentRunId");
    }

    public static AgentRunId generate() {
        return new AgentRunId(AggregateId.generateValue());
    }

    public static AgentRunId from(String value) {
        return new AgentRunId(AggregateId.parseCanonical(value, "AgentRunId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
