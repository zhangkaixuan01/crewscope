package io.crewscope.application.team;

import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/** Committed acceptance coordinates used by HTTP acknowledgement and tests. */
public record TeamInvitationAcceptanceResult(
        TeamInvitation invitation,
        TeamMember membership,
        InvitationMembershipDisposition membershipDisposition,
        boolean roleGrantCreated) {

    public TeamInvitationAcceptanceResult {
        invitation = Objects.requireNonNull(invitation, "invitation");
        membership = Objects.requireNonNull(membership, "membership");
        membershipDisposition =
                Objects.requireNonNull(membershipDisposition, "membershipDisposition");
        if (!invitation.acceptedMemberId().filter(membership.id()::equals).isPresent()) {
            throw new IllegalArgumentException("accepted invitation must reference the membership");
        }
    }
}
