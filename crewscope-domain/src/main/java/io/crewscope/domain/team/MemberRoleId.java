package io.crewscope.domain.team;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed member-role grant aggregate identifier. */
public record MemberRoleId(UUID value) implements AggregateId {

    public MemberRoleId {
        value = AggregateId.requireValue(value, "MemberRoleId");
    }

    public static MemberRoleId generate() {
        return new MemberRoleId(AggregateId.generateValue());
    }

    public static MemberRoleId from(String value) {
        return new MemberRoleId(AggregateId.parseCanonical(value, "MemberRoleId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
