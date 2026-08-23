package io.crewscope.domain.action;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one merged external Provider object projection. */
public record ExternalResultId(UUID value) implements AggregateId {

    public ExternalResultId {
        value = AggregateId.requireValue(value, "ExternalResultId");
    }

    public static ExternalResultId generate() {
        return new ExternalResultId(AggregateId.generateValue());
    }
}
