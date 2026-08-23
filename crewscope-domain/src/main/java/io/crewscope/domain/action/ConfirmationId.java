package io.crewscope.domain.action;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one member authorization for an exact ActionBundle digest. */
public record ConfirmationId(UUID value) implements AggregateId {

    public ConfirmationId {
        value = AggregateId.requireValue(value, "ConfirmationId");
    }

    public static ConfirmationId generate() {
        return new ConfirmationId(AggregateId.generateValue());
    }
}
