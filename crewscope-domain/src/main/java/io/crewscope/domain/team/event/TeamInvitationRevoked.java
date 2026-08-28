package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.BuiltInTeamRole;
import java.util.Objects;

/** Version 1 invitation revocation fact without target identity or token material. */
public record TeamInvitationRevoked(BuiltInTeamRole targetRole, boolean targetRestricted)
        implements DomainEvent {

    public TeamInvitationRevoked {
        targetRole = Objects.requireNonNull(targetRole, "targetRole");
        if (targetRole == BuiltInTeamRole.TEAM_OWNER) {
            throw new IllegalArgumentException("TEAM_OWNER must use the ownership transfer flow");
        }
    }
}
