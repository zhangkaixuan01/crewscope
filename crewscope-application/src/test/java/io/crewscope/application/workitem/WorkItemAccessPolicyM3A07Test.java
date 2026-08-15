package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Policy evidence for the M3-A07 platform-admin and Team-wide operations boundary. */
class WorkItemAccessPolicyM3A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T12:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId principalId = PrincipalId.generate();
    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final TeamMembershipQuery membershipQuery = mock(TeamMembershipQuery.class);
    private final TeamRoleRepository teamRoleRepository = mock(TeamRoleRepository.class);
    private final MemberRoleRepository memberRoleRepository = mock(MemberRoleRepository.class);
    private final Team team = mock(Team.class);
    private final Principal actor = mock(Principal.class);
    private final WorkItemAccessPolicy policy = new WorkItemAccessPolicy(
            mock(WorkItemRepository.class),
            mock(WorkProjectRepository.class),
            teamRepository,
            membershipQuery,
            teamRoleRepository,
            memberRoleRepository);

    @BeforeEach
    void setUp() {
        when(actor.type()).thenReturn(PrincipalType.USER);
        when(actor.canAct()).thenReturn(true);
        when(actor.id()).thenReturn(principalId);
        when(actor.scope()).thenReturn(PrincipalScope.organization(organizationId));
        when(team.organizationId()).thenReturn(organizationId);
        when(team.id()).thenReturn(teamId);
        when(teamRepository.findUninitializedById(organizationId, teamId))
                .thenReturn(Optional.empty());
        when(teamRepository.findById(organizationId, teamId)).thenReturn(Optional.of(team));
    }

    @Test
    void platformAdministratorCanObserveWithoutTeamMembership() {
        TeamAccessContext context = new TeamAccessContext(actor, true);

        assertDoesNotThrow(() -> policy.requireTeamPermission(
                context,
                organizationId,
                teamId,
                TeamPermission.TEAM_OBSERVE,
                NOW,
                "observe Runtime operations details"));

        verify(membershipQuery, never()).findByTeam(organizationId, teamId);
    }

    @Test
    void activeMemberWithEffectiveTeamGrantCanObserve() {
        TeamMember member = activeMember();
        TeamRoleId roleId = TeamRoleId.generate();
        TeamRole role = role(roleId);
        MemberRole grant = grant(member, roleId, RoleScope.team());
        when(membershipQuery.findByTeam(organizationId, teamId)).thenReturn(List.of(member));
        when(teamRoleRepository.findByTeam(organizationId, teamId)).thenReturn(List.of(role));
        when(memberRoleRepository.findByMember(organizationId, member.id()))
                .thenReturn(List.of(grant));

        assertDoesNotThrow(() -> policy.requireTeamPermission(
                new TeamAccessContext(actor, false),
                organizationId,
                teamId,
                TeamPermission.TEAM_OBSERVE,
                NOW,
                "observe Runtime operations details"));
    }

    @Test
    void projectScopedGrantCannotAuthorizeTeamOperations() {
        TeamMember member = activeMember();
        TeamRoleId roleId = TeamRoleId.generate();
        TeamRole role = role(roleId);
        MemberRole grant = grant(
                member, roleId, RoleScope.workProject(WorkProjectId.generate()));
        when(membershipQuery.findByTeam(organizationId, teamId)).thenReturn(List.of(member));
        when(teamRoleRepository.findByTeam(organizationId, teamId)).thenReturn(List.of(role));
        when(memberRoleRepository.findByMember(organizationId, member.id()))
                .thenReturn(List.of(grant));

        assertThrows(PolicyDeniedException.class, () -> policy.requireTeamPermission(
                new TeamAccessContext(actor, false),
                organizationId,
                teamId,
                TeamPermission.TEAM_OBSERVE,
                NOW,
                "observe Runtime operations details"));
    }

    private TeamMember activeMember() {
        TeamMember member = mock(TeamMember.class);
        when(member.id()).thenReturn(TeamMemberId.generate());
        when(member.scope()).thenReturn(new TeamScope(organizationId, teamId));
        when(member.userPrincipalId()).thenReturn(principalId);
        when(member.canParticipate()).thenReturn(true);
        return member;
    }

    private TeamRole role(TeamRoleId roleId) {
        TeamRole role = mock(TeamRole.class);
        when(role.id()).thenReturn(roleId);
        when(role.isGrantable()).thenReturn(true);
        when(role.permissions()).thenReturn(Set.of(TeamPermission.TEAM_OBSERVE));
        return role;
    }

    private MemberRole grant(
            TeamMember member, TeamRoleId roleId, RoleScope roleScope) {
        MemberRole grant = mock(MemberRole.class);
        when(grant.status()).thenReturn(MemberRoleStatus.ACTIVE);
        when(grant.isEffectiveAt(NOW)).thenReturn(true);
        when(grant.roleScope()).thenReturn(roleScope);
        when(grant.teamRoleId()).thenReturn(roleId);
        return grant;
    }
}
