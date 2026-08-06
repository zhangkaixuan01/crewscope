package io.crewscope.domain.shared.id;

import java.util.UUID;

/** Strongly typed Team aggregate identifier. */
public record TeamId(UUID value) implements AggregateId {

    public TeamId {
        value = AggregateId.requireValue(value, "TeamId");
    }

    public static TeamId generate() {
        return new TeamId(AggregateId.generateValue());
    }

    public static TeamId from(String value) {
        return new TeamId(AggregateId.parseCanonical(value, "TeamId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
