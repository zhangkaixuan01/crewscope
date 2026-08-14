package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one durable Agent interruption. */
public record AgentInterruptId(UUID value) implements AggregateId {
    public AgentInterruptId {
        value = AggregateId.requireValue(value, "AgentInterruptId");
    }

    public static AgentInterruptId generate() {
        return new AgentInterruptId(AggregateId.generateValue());
    }

    public static AgentInterruptId from(String value) {
        return new AgentInterruptId(AggregateId.parseCanonical(value, "AgentInterruptId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
