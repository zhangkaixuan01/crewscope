package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.team.BuiltInTeamRole;
import java.util.Objects;
import java.util.UUID;

/** Version 1 invitation acceptance fact with stable account and Membership results. */
public record TeamInvitationAccepted(
        UUID acceptedAccountId,
        UUID acceptedMemberId,
        BuiltInTeamRole targetRole,
        TeamInvitationMembershipResult membershipResult) implements DomainEvent {

    public TeamInvitationAccepted {
        acceptedAccountId = requireId(acceptedAccountId, "acceptedAccountId");
        acceptedMemberId = requireId(acceptedMemberId, "acceptedMemberId");
        targetRole = Objects.requireNonNull(targetRole, "targetRole");
        if (targetRole == BuiltInTeamRole.TEAM_OWNER) {
            throw new IllegalArgumentException("TEAM_OWNER must use the ownership transfer flow");
        }
        membershipResult = Objects.requireNonNull(membershipResult, "membershipResult");
    }

    private static UUID requireId(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }
}
