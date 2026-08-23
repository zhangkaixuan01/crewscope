package io.crewscope.domain.model;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier of one stable model connection. */
public record ModelConnectionId(UUID value) implements AggregateId {

    public ModelConnectionId {
        value = AggregateId.requireValue(value, "ModelConnectionId");
    }

    public static ModelConnectionId generate() {
        return new ModelConnectionId(AggregateId.generateValue());
    }

    public static ModelConnectionId from(String value) {
        return new ModelConnectionId(AggregateId.parseCanonical(value, "ModelConnectionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
