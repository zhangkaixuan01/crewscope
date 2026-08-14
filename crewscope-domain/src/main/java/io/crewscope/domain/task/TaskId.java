package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed durable business Task identifier. */
public record TaskId(UUID value) implements AggregateId {

    public TaskId {
        value = AggregateId.requireValue(value, "TaskId");
    }

    public static TaskId generate() {
        return new TaskId(AggregateId.generateValue());
    }

    public static TaskId from(String value) {
        return new TaskId(AggregateId.parseCanonical(value, "TaskId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
