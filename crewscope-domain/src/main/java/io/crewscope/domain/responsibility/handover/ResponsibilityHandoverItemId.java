package io.crewscope.domain.responsibility.handover;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed responsibility-handover-item aggregate identifier. */
public record ResponsibilityHandoverItemId(UUID value) implements AggregateId {

    public ResponsibilityHandoverItemId {
        value = AggregateId.requireValue(value, "ResponsibilityHandoverItemId");
    }

    public static ResponsibilityHandoverItemId generate() {
        return new ResponsibilityHandoverItemId(AggregateId.generateValue());
    }

    public static ResponsibilityHandoverItemId from(String value) {
        return new ResponsibilityHandoverItemId(
                AggregateId.parseCanonical(value, "ResponsibilityHandoverItemId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
