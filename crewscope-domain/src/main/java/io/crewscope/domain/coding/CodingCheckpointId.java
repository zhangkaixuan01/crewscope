package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one immutable Coding recovery checkpoint. */
public record CodingCheckpointId(UUID value) implements AggregateId {

    public CodingCheckpointId {
        value = AggregateId.requireValue(value, "CodingCheckpointId");
    }

    public static CodingCheckpointId generate() {
        return new CodingCheckpointId(AggregateId.generateValue());
    }

    public static CodingCheckpointId from(String value) {
        return new CodingCheckpointId(AggregateId.parseCanonical(value, "CodingCheckpointId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
