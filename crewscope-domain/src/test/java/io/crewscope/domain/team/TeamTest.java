package io.crewscope.domain.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TeamTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId ACTOR_ID = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T15:00:00Z");

    @Test
    void createsAnActiveTeamWithStableOwnerAndWorkspaceReferences() {
        TeamMemberId ownerMemberId = TeamMemberId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();

        Team team = Team.create(
                TeamId.generate(),
                ORGANIZATION_ID,
                "  Platform Crew  ",
                ownerMemberId,
                workspaceId,
                ACTOR_ID,
                CREATED_AT);

        assertEquals("Platform Crew", team.name());
        assertEquals(ownerMemberId, team.ownerMemberId());
        assertEquals(workspaceId, team.defaultWorkspaceId());
        assertEquals(TeamStatus.ACTIVE, team.status());
        assertEquals(ACTOR_ID, team.audit().createdBy().orElseThrow());
        assertEquals(0, team.version());
    }

    @Test
    void activeTeamCreatesMembersAndArchivedTeamRejectsMembershipChanges() {
        Team team = team();
        Principal user = activeUser(ORGANIZATION_ID);

        TeamMember joined = team.joinMember(
                TeamMemberId.generate(), user, TeamJoinMethod.OIDC, CREATED_AT);
        Team archived = team.archive(
                ACTOR_ID, UtcTimestamp.parse("2026-08-07T15:01:00Z"));

        assertTrue(joined.canParticipate());
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> archived.inviteMember(
                        TeamMemberId.generate(),
                        activeUser(ORGANIZATION_ID),
                        ACTOR_ID,
                        UtcTimestamp.parse("2026-08-07T15:02:00Z")));
        assertEquals("team.status", failure.error().details().get("field"));
    }

    @Test
    void transfersOwnershipOnlyToAnActiveMemberOfTheSameTeam() {
        Team team = team();
        Principal user = activeUser(ORGANIZATION_ID);
        TeamMember member = team.joinMember(
                TeamMemberId.generate(), user, TeamJoinMethod.OIDC, CREATED_AT);

        Team transferred = team.transferOwnership(
                member, ACTOR_ID, UtcTimestamp.parse("2026-08-07T15:01:00Z"));

        assertTrue(transferred.isOwner(member.id()));
        assertEquals(1, transferred.version());
        TeamScope otherScope = new TeamScope(ORGANIZATION_ID, TeamId.generate());
        TeamMember outsider = TeamMember.join(
                TeamMemberId.generate(),
                otherScope,
                activeUser(ORGANIZATION_ID),
                TeamJoinMethod.OIDC,
                CREATED_AT);
        assertThrows(
                DomainValidationException.class,
                () -> team.transferOwnership(
                        outsider,
                        ACTOR_ID,
                        UtcTimestamp.parse("2026-08-07T15:01:00Z")));
    }

    @Test
    void archiveIsTerminalAndAdvancesAuditMetadata() {
        Team archived = team().archive(
                ACTOR_ID, UtcTimestamp.parse("2026-08-07T15:01:00Z"));

        assertFalse(archived.isActive());
        assertEquals(1, archived.version());
        assertEquals(
                UtcTimestamp.parse("2026-08-07T15:01:00Z"),
                archived.audit().updatedAt());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.archive(
                        ACTOR_ID, UtcTimestamp.parse("2026-08-07T15:02:00Z")));
    }

    private static Team team() {
        return Team.create(
                TeamId.generate(),
                ORGANIZATION_ID,
                "Platform Crew",
                TeamMemberId.generate(),
                WorkspaceId.generate(),
                ACTOR_ID,
                CREATED_AT);
    }

    static Principal activeUser(OrganizationId organizationId) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
