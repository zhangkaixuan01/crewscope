package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import java.util.Objects;

/** Version 1 invitation creation fact without target email, token or token digest. */
public record TeamInvitationCreated(
        BuiltInTeamRole targetRole, boolean targetRestricted, UtcTimestamp expiresAt)
        implements DomainEvent {

    public TeamInvitationCreated {
        targetRole = requireInvitableRole(targetRole);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static BuiltInTeamRole requireInvitableRole(BuiltInTeamRole role) {
        BuiltInTeamRole required = Objects.requireNonNull(role, "targetRole");
        if (required == BuiltInTeamRole.TEAM_OWNER) {
            throw new IllegalArgumentException("TEAM_OWNER must use the ownership transfer flow");
        }
        return required;
    }
}
