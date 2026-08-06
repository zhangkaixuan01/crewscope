package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed WorkItem aggregate identifier. */
public record WorkItemId(UUID value) implements AggregateId {

    public WorkItemId {
        value = AggregateId.requireValue(value, "WorkItemId");
    }

    public static WorkItemId generate() {
        return new WorkItemId(AggregateId.generateValue());
    }

    public static WorkItemId from(String value) {
        return new WorkItemId(AggregateId.parseCanonical(value, "WorkItemId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
