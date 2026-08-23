package io.crewscope.domain.action;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one externally observable side effect. */
public record PlannedActionId(UUID value) implements AggregateId {

    public PlannedActionId {
        value = AggregateId.requireValue(value, "PlannedActionId");
    }

    public static PlannedActionId generate() {
        return new PlannedActionId(AggregateId.generateValue());
    }
}
