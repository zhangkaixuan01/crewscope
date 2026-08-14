package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one durable execution of a published Plan step. */
public record StepExecutionId(UUID value) implements AggregateId {
    public StepExecutionId {
        value = AggregateId.requireValue(value, "StepExecutionId");
    }

    public static StepExecutionId generate() {
        return new StepExecutionId(AggregateId.generateValue());
    }

    public static StepExecutionId from(String value) {
        return new StepExecutionId(AggregateId.parseCanonical(value, "StepExecutionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
