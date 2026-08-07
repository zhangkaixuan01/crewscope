package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one WorkItem resource relation. */
public record WorkItemResourceLinkId(UUID value) implements AggregateId {

    public WorkItemResourceLinkId {
        value = AggregateId.requireValue(value, "WorkItemResourceLinkId");
    }

    public static WorkItemResourceLinkId generate() {
        return new WorkItemResourceLinkId(AggregateId.generateValue());
    }

    public static WorkItemResourceLinkId from(String value) {
        return new WorkItemResourceLinkId(
                AggregateId.parseCanonical(value, "WorkItemResourceLinkId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
