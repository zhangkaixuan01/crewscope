package io.crewscope.application.team;

import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationStatus;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/** Atomic persistence plan for invitation, Membership and the requested built-in role. */
public record TeamInvitationAcceptancePlan(
        TeamInvitation invitation,
        TeamMember membership,
        InvitationMembershipDisposition membershipDisposition,
        BuiltInTeamRole targetRole) {

    public TeamInvitationAcceptancePlan {
        invitation = Objects.requireNonNull(invitation, "invitation");
        membership = Objects.requireNonNull(membership, "membership");
        membershipDisposition = Objects.requireNonNull(
                membershipDisposition, "membershipDisposition");
        targetRole = Objects.requireNonNull(targetRole, "targetRole");
        if (invitation.status() != TeamInvitationStatus.ACCEPTED
                || !invitation.scope().equals(membership.scope())
                || !invitation.acceptedMemberId().filter(membership.id()::equals).isPresent()
                || invitation.targetRole() != targetRole
                || !membership.canParticipate()) {
            throw new IllegalArgumentException(
                    "acceptance plan must contain one accepted invitation and active Membership");
        }
    }

    public boolean createsMembership() {
        return membershipDisposition == InvitationMembershipDisposition.CREATED;
    }

    public boolean updatesMembership() {
        return membershipDisposition == InvitationMembershipDisposition.ACTIVATED;
    }
}
