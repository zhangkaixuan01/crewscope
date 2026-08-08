package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed TaskIntent aggregate identifier. */
public record TaskIntentId(UUID value) implements AggregateId {

    public TaskIntentId {
        value = AggregateId.requireValue(value, "TaskIntentId");
    }

    public static TaskIntentId generate() {
        return new TaskIntentId(AggregateId.generateValue());
    }

    public static TaskIntentId from(String value) {
        return new TaskIntentId(AggregateId.parseCanonical(value, "TaskIntentId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
