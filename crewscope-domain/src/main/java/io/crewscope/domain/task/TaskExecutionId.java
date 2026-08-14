package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one Task execution attempt introduced by M3-D02. */
public record TaskExecutionId(UUID value) implements AggregateId {

    public TaskExecutionId {
        value = AggregateId.requireValue(value, "TaskExecutionId");
    }

    public static TaskExecutionId generate() {
        return new TaskExecutionId(AggregateId.generateValue());
    }

    public static TaskExecutionId from(String value) {
        return new TaskExecutionId(AggregateId.parseCanonical(value, "TaskExecutionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
