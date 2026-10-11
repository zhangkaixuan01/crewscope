package io.crewscope.domain.workgraph;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed WorkItemRelation aggregate identifier. */
public record WorkItemRelationId(UUID value) implements AggregateId {

    public WorkItemRelationId {
        value = AggregateId.requireValue(value, "WorkItemRelationId");
    }

    public static WorkItemRelationId generate() {
        return new WorkItemRelationId(AggregateId.generateValue());
    }

    public static WorkItemRelationId from(String value) {
        return new WorkItemRelationId(AggregateId.parseCanonical(value, "WorkItemRelationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
