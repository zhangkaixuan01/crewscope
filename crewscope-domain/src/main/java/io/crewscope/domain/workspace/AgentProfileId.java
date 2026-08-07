package io.crewscope.domain.workspace;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one durable Agent configuration profile. */
public record AgentProfileId(UUID value) implements AggregateId {

    public AgentProfileId {
        value = AggregateId.requireValue(value, "AgentProfileId");
    }

    public static AgentProfileId generate() {
        return new AgentProfileId(AggregateId.generateValue());
    }

    public static AgentProfileId from(String value) {
        return new AgentProfileId(AggregateId.parseCanonical(value, "AgentProfileId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
