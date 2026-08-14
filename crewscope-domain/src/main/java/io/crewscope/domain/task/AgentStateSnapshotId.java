package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one committed AgentState snapshot metadata record. */
public record AgentStateSnapshotId(UUID value) implements AggregateId {
    public AgentStateSnapshotId {
        value = AggregateId.requireValue(value, "AgentStateSnapshotId");
    }

    public static AgentStateSnapshotId generate() {
        return new AgentStateSnapshotId(AggregateId.generateValue());
    }

    public static AgentStateSnapshotId from(String value) {
        return new AgentStateSnapshotId(AggregateId.parseCanonical(value, "AgentStateSnapshotId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
