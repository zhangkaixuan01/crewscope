package io.crewscope.application.team;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import java.util.Objects;
import java.util.Optional;

/** Builds the Membership and invitation changes that must commit in one acceptance transaction. */
public final class TeamInvitationAcceptanceService {

    /**
     * Reuses a current active Membership, activates an eligible historical Membership, or creates
     * one stable invitation Membership. Suspended Memberships require an administrator decision and
     * cannot be bypassed with a token.
     */
    public TeamInvitationAcceptancePlan planAcceptance(
            TeamInvitation invitation,
            InvitationTokenDigest presentedDigest,
            UserAccount account,
            AccountOrganizationBinding binding,
            Team team,
            Principal userPrincipal,
            Optional<TeamMember> existingMembership,
            TeamMemberId newMembershipId,
            UtcTimestamp occurredAt) {
        TeamInvitation requiredInvitation = Objects.requireNonNull(invitation, "invitation");
        AccountOrganizationBinding requiredBinding =
                Objects.requireNonNull(binding, "binding");
        Principal requiredPrincipal = Objects.requireNonNull(userPrincipal, "userPrincipal");
        if (!requiredBinding.isCompatibleWith(requiredPrincipal)) {
            throw new DomainValidationException(
                    "teamInvitation.binding",
                    "must resolve to the accepting active USER Principal");
        }
        MembershipSelection selection = selectMembership(
                requiredInvitation,
                Objects.requireNonNull(team, "team"),
                requiredPrincipal,
                Objects.requireNonNull(existingMembership, "existingMembership"),
                Objects.requireNonNull(newMembershipId, "newMembershipId"),
                Objects.requireNonNull(occurredAt, "occurredAt"));
        TeamInvitation accepted = requiredInvitation.accept(
                Objects.requireNonNull(account, "account"),
                requiredBinding,
                requiredPrincipal,
                team,
                selection.membership(),
                Objects.requireNonNull(presentedDigest, "presentedDigest"),
                occurredAt);
        return new TeamInvitationAcceptancePlan(
                accepted,
                selection.membership(),
                selection.disposition(),
                requiredInvitation.targetRole());
    }

    private static MembershipSelection selectMembership(
            TeamInvitation invitation,
            Team team,
            Principal principal,
            Optional<TeamMember> existingMembership,
            TeamMemberId newMembershipId,
            UtcTimestamp occurredAt) {
        if (!invitation.scope().equals(team.scope())) {
            throw new DomainValidationException(
                    "teamInvitation.team", "must match the invitation Team");
        }
        if (existingMembership.isEmpty()) {
            return new MembershipSelection(
                    team.acceptInvitedMember(
                            newMembershipId,
                            principal,
                            invitation.invitedByPrincipalId(),
                            occurredAt),
                    InvitationMembershipDisposition.CREATED);
        }

        TeamMember existing = existingMembership.orElseThrow();
        requireMatchingMembership(existing, invitation, principal);
        return switch (existing.status()) {
            case ACTIVE -> new MembershipSelection(
                    existing, InvitationMembershipDisposition.REUSED);
            case INVITED -> new MembershipSelection(
                    activatePendingInvitation(existing, invitation, principal, occurredAt),
                    InvitationMembershipDisposition.ACTIVATED);
            case LEFT -> new MembershipSelection(
                    existing.activate(principal, occurredAt),
                    InvitationMembershipDisposition.ACTIVATED);
            case REMOVED -> new MembershipSelection(
                    existing.reinvite(principal, invitation.invitedByPrincipalId(), occurredAt)
                            .activate(principal, occurredAt),
                    InvitationMembershipDisposition.ACTIVATED);
            case SUSPENDED -> throw new DomainValidationException(
                    "teamInvitation.membership",
                    "a suspended Membership requires administrator reactivation");
        };
    }

    private static TeamMember activatePendingInvitation(
            TeamMember membership,
            TeamInvitation invitation,
            Principal principal,
            UtcTimestamp occurredAt) {
        if (!membership.invitedByPrincipalId()
                .filter(invitation.invitedByPrincipalId()::equals)
                .isPresent()) {
            throw new DomainValidationException(
                    "teamInvitation.membership",
                    "a pending Membership must belong to the consumed invitation source");
        }
        return membership.activate(principal, occurredAt);
    }

    private static void requireMatchingMembership(
            TeamMember membership, TeamInvitation invitation, Principal principal) {
        if (!membership.scope().equals(invitation.scope())
                || !membership.userPrincipalId().equals(principal.id())) {
            throw new DomainValidationException(
                    "teamInvitation.membership",
                    "must belong to the invitation Team and accepting USER Principal");
        }
    }

    private record MembershipSelection(
            TeamMember membership, InvitationMembershipDisposition disposition) {

        private MembershipSelection {
            membership = Objects.requireNonNull(membership, "membership");
            disposition = Objects.requireNonNull(disposition, "disposition");
        }
    }
}
