package io.crewscope.domain.team;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of one Team invitation. */
public record TeamInvitationId(UUID value) implements AggregateId {

    public TeamInvitationId {
        value = AggregateId.requireValue(value, "TeamInvitationId");
    }

    public static TeamInvitationId generate() {
        return new TeamInvitationId(AggregateId.generateValue());
    }

    public static TeamInvitationId from(String value) {
        return new TeamInvitationId(AggregateId.parseCanonical(value, "TeamInvitationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
