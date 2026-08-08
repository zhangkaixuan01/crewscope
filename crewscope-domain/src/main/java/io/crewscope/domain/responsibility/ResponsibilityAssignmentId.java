package io.crewscope.domain.responsibility;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed responsibility-assignment aggregate identifier. */
public record ResponsibilityAssignmentId(UUID value) implements AggregateId {

    public ResponsibilityAssignmentId {
        value = AggregateId.requireValue(value, "ResponsibilityAssignmentId");
    }

    public static ResponsibilityAssignmentId generate() {
        return new ResponsibilityAssignmentId(AggregateId.generateValue());
    }

    public static ResponsibilityAssignmentId from(String value) {
        return new ResponsibilityAssignmentId(
                AggregateId.parseCanonical(value, "ResponsibilityAssignmentId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
