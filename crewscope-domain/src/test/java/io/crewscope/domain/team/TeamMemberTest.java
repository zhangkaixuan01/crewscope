package io.crewscope.domain.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamMemberTest {

    private static final TeamScope SCOPE =
            new TeamScope(OrganizationId.generate(), TeamId.generate());
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T11:00:00Z");

    @Test
    void joinsAnActiveUserThroughATrustedIdentitySource() {
        TeamMember member = TeamMember.join(
                TeamMemberId.generate(), SCOPE, activeUser(), TeamJoinMethod.OIDC, CREATED_AT);

        assertEquals(TeamMemberStatus.ACTIVE, member.status());
        assertEquals(TeamJoinMethod.OIDC, member.joinMethod());
        assertEquals(CREATED_AT, member.joinedAt().orElseThrow());
        assertTrue(member.canParticipate());
    }

    @Test
    void invitationKeepsInviterAndActivatesOnAcceptance() {
        PrincipalId inviter = PrincipalId.generate();
        Principal user = activeUser();
        TeamMember invited = TeamMember.invite(
                TeamMemberId.generate(), SCOPE, user, inviter, CREATED_AT);

        TeamMember active = invited.activate(
                user, UtcTimestamp.parse("2026-08-07T11:05:00Z"));

        assertEquals(TeamMemberStatus.INVITED, invited.status());
        assertTrue(invited.joinedAt().isEmpty());
        assertEquals(inviter, active.invitedByPrincipalId().orElseThrow());
        assertEquals(TeamMemberStatus.ACTIVE, active.status());
        assertEquals(1, active.version());
    }

    @Test
    void rejectsAgentAndDisabledUserAsMemberIdentity() {
        Principal agent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(SCOPE.organizationId()),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(PrincipalId.generate()),
                "Agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                CREATED_AT);
        Principal disabledUser = activeUser().transitionTo(
                PrincipalStatus.DISABLED,
                UtcTimestamp.parse("2026-08-07T11:01:00Z"));

        DomainValidationException agentFailure = assertThrows(
                DomainValidationException.class,
                () -> TeamMember.join(
                        TeamMemberId.generate(),
                        SCOPE,
                        agent,
                        TeamJoinMethod.OIDC,
                        CREATED_AT));
        DomainValidationException disabledFailure = assertThrows(
                DomainValidationException.class,
                () -> TeamMember.join(
                        TeamMemberId.generate(),
                        SCOPE,
                        disabledUser,
                        TeamJoinMethod.OIDC,
                        UtcTimestamp.parse("2026-08-07T11:02:00Z")));

        assertEquals("teamMember.userPrincipalId", agentFailure.error().details().get("field"));
        assertEquals("teamMember.userPrincipalId", disabledFailure.error().details().get("field"));
    }

    @Test
    void invitationCannotBypassItsExplicitFlow() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> TeamMember.join(
                        TeamMemberId.generate(),
                        SCOPE,
                        activeUser(),
                        TeamJoinMethod.INVITATION,
                        CREATED_AT));

        assertEquals("teamMember.joinMethod", failure.error().details().get("field"));
    }

    @Test
    void suspensionImmediatelyRemovesParticipationAndPresenceWrites() {
        TeamMember suspended = activeMember().suspend(
                UtcTimestamp.parse("2026-08-07T11:10:00Z"));

        assertFalse(suspended.canParticipate());
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> suspended.recordActivity(
                        UtcTimestamp.parse("2026-08-07T11:11:00Z")));
        assertEquals("teamMember.lastActiveAt", failure.error().details().get("field"));
    }

    @Test
    void activeMemberRecordsMonotonicPresence() {
        TeamMember active = activeMember()
                .recordActivity(UtcTimestamp.parse("2026-08-07T11:01:00Z"))
                .recordActivity(UtcTimestamp.parse("2026-08-07T11:02:00Z"));

        assertEquals(UtcTimestamp.parse("2026-08-07T11:02:00Z"), active.lastActiveAt().orElseThrow());
        assertEquals(2, active.version());
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> active.recordActivity(
                        UtcTimestamp.parse("2026-08-07T11:01:30Z")));
        assertEquals("teamMember.lastActiveAt", failure.error().details().get("field"));
    }

    @Test
    void leftMemberCanRejoinTheDurableMembership() {
        Principal user = activeUser();
        TeamMember rejoined = TeamMember.join(
                        TeamMemberId.generate(), SCOPE, user, TeamJoinMethod.BOOTSTRAP, CREATED_AT)
                .recordActivity(UtcTimestamp.parse("2026-08-07T11:01:00Z"))
                .leave(UtcTimestamp.parse("2026-08-07T11:02:00Z"))
                .activate(user, UtcTimestamp.parse("2026-08-07T12:00:00Z"));

        assertTrue(rejoined.canParticipate());
        assertEquals(UtcTimestamp.parse("2026-08-07T12:00:00Z"), rejoined.joinedAt().orElseThrow());
        assertTrue(rejoined.lastActiveAt().isEmpty());
    }

    @Test
    void removedMembershipRequiresANewInvitationBeforeActivation() {
        Principal user = activeUser();
        TeamMember removed = activeMember().remove(
                UtcTimestamp.parse("2026-08-07T11:01:00Z"));

        InvalidStateTransitionException failure = assertThrows(
                InvalidStateTransitionException.class,
                () -> removed.activate(
                        user, UtcTimestamp.parse("2026-08-07T11:02:00Z")));
        assertEquals("REMOVED", failure.error().details().get("currentState"));
    }

    @Test
    void administrativelyRemovedMemberCanBeReinvitedUsingTheStableMembership() {
        Principal user = activeUser();
        TeamMember removed = TeamMember.join(
                        TeamMemberId.generate(), SCOPE, user, TeamJoinMethod.OIDC, CREATED_AT)
                .remove(UtcTimestamp.parse("2026-08-07T11:01:00Z"));
        PrincipalId inviter = PrincipalId.generate();

        TeamMember reinvited = removed.reinvite(
                user, inviter, UtcTimestamp.parse("2026-08-07T11:02:00Z"));

        assertEquals(removed.id(), reinvited.id());
        assertEquals(TeamMemberStatus.INVITED, reinvited.status());
        assertEquals(TeamJoinMethod.INVITATION, reinvited.joinMethod());
        assertEquals(inviter, reinvited.invitedByPrincipalId().orElseThrow());
        assertTrue(reinvited.joinedAt().isEmpty());
    }

    @Test
    void rejectsCrossOrganizationMembershipAndDisabledUserActivation() {
        Principal otherOrganizationUser = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(OrganizationId.generate()),
                PrincipalType.USER,
                Optional.empty(),
                "Other Organization User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
        DomainValidationException crossOrganization = assertThrows(
                DomainValidationException.class,
                () -> TeamMember.join(
                        TeamMemberId.generate(),
                        SCOPE,
                        otherOrganizationUser,
                        TeamJoinMethod.OIDC,
                        CREATED_AT));

        Principal user = activeUser();
        TeamMember invited = TeamMember.invite(
                TeamMemberId.generate(), SCOPE, user, PrincipalId.generate(), CREATED_AT);
        Principal disabled = user.transitionTo(
                PrincipalStatus.DISABLED,
                UtcTimestamp.parse("2026-08-07T11:01:00Z"));
        DomainValidationException inactive = assertThrows(
                DomainValidationException.class,
                () -> invited.activate(
                        disabled, UtcTimestamp.parse("2026-08-07T11:02:00Z")));

        assertEquals(
                "teamMember.userPrincipalId",
                crossOrganization.error().details().get("field"));
        assertEquals("teamMember.userPrincipalId", inactive.error().details().get("field"));
    }

    private static TeamMember activeMember() {
        return TeamMember.join(
                TeamMemberId.generate(), SCOPE, activeUser(), TeamJoinMethod.BOOTSTRAP, CREATED_AT);
    }

    private static Principal activeUser() {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(SCOPE.organizationId()),
                PrincipalType.USER,
                Optional.empty(),
                "User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
