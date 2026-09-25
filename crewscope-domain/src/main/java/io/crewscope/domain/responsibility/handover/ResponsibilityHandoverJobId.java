package io.crewscope.domain.responsibility.handover;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed responsibility-handover-job aggregate identifier. */
public record ResponsibilityHandoverJobId(UUID value) implements AggregateId {

    public ResponsibilityHandoverJobId {
        value = AggregateId.requireValue(value, "ResponsibilityHandoverJobId");
    }

    public static ResponsibilityHandoverJobId generate() {
        return new ResponsibilityHandoverJobId(AggregateId.generateValue());
    }

    public static ResponsibilityHandoverJobId from(String value) {
        return new ResponsibilityHandoverJobId(
                AggregateId.parseCanonical(value, "ResponsibilityHandoverJobId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
