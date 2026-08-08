package io.crewscope.domain.provider;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed external Connection identifier. */
public record ConnectionId(UUID value) implements AggregateId {

    public ConnectionId {
        value = AggregateId.requireValue(value, "ConnectionId");
    }

    public static ConnectionId generate() {
        return new ConnectionId(AggregateId.generateValue());
    }

    public static ConnectionId from(String value) {
        return new ConnectionId(AggregateId.parseCanonical(value, "ConnectionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
