package io.crewscope.domain.team;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed Team membership aggregate identifier. */
public record TeamMemberId(UUID value) implements AggregateId {

    public TeamMemberId {
        value = AggregateId.requireValue(value, "TeamMemberId");
    }

    public static TeamMemberId generate() {
        return new TeamMemberId(AggregateId.generateValue());
    }

    public static TeamMemberId from(String value) {
        return new TeamMemberId(AggregateId.parseCanonical(value, "TeamMemberId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
