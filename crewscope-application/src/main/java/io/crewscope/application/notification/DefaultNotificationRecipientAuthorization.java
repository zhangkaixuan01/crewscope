package io.crewscope.application.notification;

import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Authorizes manual redelivery only for the current active recipient member. */
public final class DefaultNotificationRecipientAuthorization
        implements NotificationRecipientAuthorization {

    private final TeamMemberRepository members;

    public DefaultNotificationRecipientAuthorization(TeamMemberRepository members) {
        this.members = Objects.requireNonNull(members, "members");
    }

    @Override
    public void requireActiveRecipient(
            OrganizationId organizationId,
            TeamMemberId recipientMemberId,
            Principal actor) {
        OrganizationId organization = Objects.requireNonNull(
                organizationId, "organizationId");
        TeamMemberId recipient = Objects.requireNonNull(
                recipientMemberId, "recipientMemberId");
        Principal principal = Objects.requireNonNull(actor, "actor");
        if (!principal.canAct()
                || principal.type() != PrincipalType.USER
                || !principal.scope().organizationId().equals(organization)) {
            throw denied();
        }
        members.findById(organization, recipient)
                .filter(member -> member.scope().organizationId().equals(organization))
                .filter(member -> principal.scope().teamId()
                        .filter(member.scope().teamId()::equals).isPresent())
                .filter(member -> member.userPrincipalId().equals(principal.id()))
                .filter(member -> member.canParticipate())
                .orElseThrow(DefaultNotificationRecipientAuthorization::denied);
    }

    private static PolicyDeniedException denied() {
        return new PolicyDeniedException("redeliver this notification as its active recipient");
    }
}
