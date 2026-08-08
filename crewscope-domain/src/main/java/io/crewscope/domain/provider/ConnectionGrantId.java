package io.crewscope.domain.provider;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed ConnectionGrant identifier. */
public record ConnectionGrantId(UUID value) implements AggregateId {

    public ConnectionGrantId {
        value = AggregateId.requireValue(value, "ConnectionGrantId");
    }

    public static ConnectionGrantId generate() {
        return new ConnectionGrantId(AggregateId.generateValue());
    }

    public static ConnectionGrantId from(String value) {
        return new ConnectionGrantId(
                AggregateId.parseCanonical(value, "ConnectionGrantId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
