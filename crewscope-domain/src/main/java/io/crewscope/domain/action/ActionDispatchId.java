package io.crewscope.domain.action;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of the durable scheduler row for one PlannedAction. */
public record ActionDispatchId(UUID value) implements AggregateId {

    public ActionDispatchId {
        value = AggregateId.requireValue(value, "ActionDispatchId");
    }

    public static ActionDispatchId generate() {
        return new ActionDispatchId(AggregateId.generateValue());
    }
}
