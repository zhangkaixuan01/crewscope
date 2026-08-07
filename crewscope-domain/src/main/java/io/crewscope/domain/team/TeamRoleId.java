package io.crewscope.domain.team;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed Team role-definition aggregate identifier. */
public record TeamRoleId(UUID value) implements AggregateId {

    public TeamRoleId {
        value = AggregateId.requireValue(value, "TeamRoleId");
    }

    public static TeamRoleId generate() {
        return new TeamRoleId(AggregateId.generateValue());
    }

    public static TeamRoleId from(String value) {
        return new TeamRoleId(AggregateId.parseCanonical(value, "TeamRoleId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
