package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed WorkProject aggregate identifier. */
public record WorkProjectId(UUID value) implements AggregateId {

    public WorkProjectId {
        value = AggregateId.requireValue(value, "WorkProjectId");
    }

    public static WorkProjectId generate() {
        return new WorkProjectId(AggregateId.generateValue());
    }

    public static WorkProjectId from(String value) {
        return new WorkProjectId(AggregateId.parseCanonical(value, "WorkProjectId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
