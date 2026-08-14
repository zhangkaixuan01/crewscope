package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one immutable published execution plan version. */
public record PlanVersionId(UUID value) implements AggregateId {
    public PlanVersionId {
        value = AggregateId.requireValue(value, "PlanVersionId");
    }

    public static PlanVersionId generate() {
        return new PlanVersionId(AggregateId.generateValue());
    }

    public static PlanVersionId from(String value) {
        return new PlanVersionId(AggregateId.parseCanonical(value, "PlanVersionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
