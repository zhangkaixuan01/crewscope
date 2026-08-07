package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one immutable WorkItem comment. */
public record WorkItemCommentId(UUID value) implements AggregateId {

    public WorkItemCommentId {
        value = AggregateId.requireValue(value, "WorkItemCommentId");
    }

    public static WorkItemCommentId generate() {
        return new WorkItemCommentId(AggregateId.generateValue());
    }

    public static WorkItemCommentId from(String value) {
        return new WorkItemCommentId(AggregateId.parseCanonical(value, "WorkItemCommentId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
