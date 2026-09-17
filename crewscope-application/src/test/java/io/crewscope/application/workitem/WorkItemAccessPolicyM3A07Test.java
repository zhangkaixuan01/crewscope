package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.crewscope.domain.shared.id.WorkspaceId;
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
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectScope;
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
    private final WorkProjectRepository projectRepository = mock(WorkProjectRepository.class);
    private final WorkItemAccessPolicy policy = new WorkItemAccessPolicy(
            mock(WorkItemRepository.class),
            projectRepository,
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

    /**
     * The refactor that introduced {@link WorkItemTransitionPermissionResolver} split one rule into a
     * one-shot read plus a pure answer. Both halves must still say exactly what the whole used to say,
     * so this walks the same cells the rule distinguishes and asserts the two paths agree on each —
     * and that the agreed answer is the right one, since two paths agreeing on a wrong verdict would
     * be agreement without correctness.
     */
    @Test
    void resolverAgreesWithThePerCallCheckOnEveryCellOfTheMatrix() {
        TeamMember member = activeMember();
        WorkProjectId asked = WorkProjectId.generate();
        WorkProjectId somewhereElse = WorkProjectId.generate();
        WorkProject project = project(asked);
        when(projectRepository.findById(organizationId, asked))
                .thenReturn(Optional.of(project));

        TeamRoleId teamRoleId = TeamRoleId.generate();
        TeamRoleId projectRoleId = TeamRoleId.generate();
        TeamRoleId observerRoleId = TeamRoleId.generate();
        Set<TeamPermission> participates = Set.of(TeamPermission.WORK_PARTICIPATE);

        List<Cell> cells = List.of(
                new Cell("a platform administrator may participate everywhere", true, () -> {}, true),
                new Cell(
                        "a Team-scoped grant carries the permission into any project",
                        false,
                        () -> stubFacts(
                                member,
                                List.of(role(teamRoleId, participates, true)),
                                List.of(grant(member, teamRoleId, RoleScope.team()))),
                        true),
                new Cell(
                        "a grant scoped to the asked project carries the permission",
                        false,
                        () -> stubFacts(
                                member,
                                List.of(role(projectRoleId, participates, true)),
                                List.of(grant(member, projectRoleId, RoleScope.workProject(asked)))),
                        true),
                new Cell(
                        "a grant scoped to another project does not",
                        false,
                        () -> stubFacts(
                                member,
                                List.of(role(projectRoleId, participates, true)),
                                List.of(grant(
                                        member, projectRoleId, RoleScope.workProject(somewhereElse)))),
                        false),
                new Cell(
                        "a revoked grant does not",
                        false,
                        () -> stubFacts(
                                member,
                                List.of(role(teamRoleId, participates, true)),
                                List.of(grant(
                                        member,
                                        teamRoleId,
                                        RoleScope.team(),
                                        MemberRoleStatus.REVOKED,
                                        true))),
                        false),
                new Cell(
                        "an expired grant does not",
                        false,
                        () -> stubFacts(
                                member,
                                List.of(role(teamRoleId, participates, true)),
                                List.of(grant(
                                        member,
                                        teamRoleId,
                                        RoleScope.team(),
                                        MemberRoleStatus.ACTIVE,
                                        false))),
                        false),
                new Cell(
                        "a role that may not be granted does not",
                        false,
                        () -> stubFacts(
                                member,
                                List.of(role(teamRoleId, participates, false)),
                                List.of(grant(member, teamRoleId, RoleScope.team()))),
                        false),
                new Cell(
                        "a role without the permission does not",
                        false,
                        () -> stubFacts(
                                member,
                                List.of(role(observerRoleId, Set.of(TeamPermission.TEAM_OBSERVE), true)),
                                List.of(grant(member, observerRoleId, RoleScope.team()))),
                        false),
                new Cell(
                        "a member with no grant at all does not",
                        false,
                        () -> stubFacts(member, List.of(role(teamRoleId, participates, true)), List.of()),
                        false));

        for (Cell cell : cells) {
            cell.facts().run();
            TeamAccessContext context = new TeamAccessContext(actor, cell.administrator());
            WorkItemTransitionPermissionResolver resolver =
                    policy.resolvePermission(context, organizationId, teamId, NOW);

            boolean perCall = policy.hasPermission(
                    context, organizationId, teamId, asked, TeamPermission.WORK_PARTICIPATE, NOW);
            boolean batched = resolver.granted(asked, TeamPermission.WORK_PARTICIPATE);

            assertEquals(perCall, batched, "resolver and per-call check disagree: " + cell.name());
            assertEquals(cell.expected(), batched, "wrong verdict: " + cell.name());
        }
    }

    /** One row of the agreement matrix: the facts, and the verdict both paths must reach. */
    private record Cell(String name, boolean administrator, Runnable facts, boolean expected) {}

    private void stubFacts(TeamMember member, List<TeamRole> roles, List<MemberRole> grants) {
        when(membershipQuery.findByTeam(organizationId, teamId)).thenReturn(List.of(member));
        when(teamRoleRepository.findByTeam(organizationId, teamId)).thenReturn(roles);
        when(memberRoleRepository.findByMember(organizationId, member.id())).thenReturn(grants);
    }

    private WorkProject project(WorkProjectId id) {
        WorkProject project = mock(WorkProject.class);
        when(project.id()).thenReturn(id);
        when(project.scope())
                .thenReturn(new WorkProjectScope(organizationId, teamId, WorkspaceId.generate()));
        return project;
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
        return role(roleId, Set.of(TeamPermission.TEAM_OBSERVE), true);
    }

    private TeamRole role(
            TeamRoleId roleId, Set<TeamPermission> permissions, boolean grantable) {
        TeamRole role = mock(TeamRole.class);
        when(role.id()).thenReturn(roleId);
        when(role.isGrantable()).thenReturn(grantable);
        when(role.permissions()).thenReturn(permissions);
        return role;
    }

    private MemberRole grant(
            TeamMember member, TeamRoleId roleId, RoleScope roleScope) {
        return grant(member, roleId, roleScope, MemberRoleStatus.ACTIVE, true);
    }

    private MemberRole grant(
            TeamMember member,
            TeamRoleId roleId,
            RoleScope roleScope,
            MemberRoleStatus status,
            boolean effective) {
        MemberRole grant = mock(MemberRole.class);
        when(grant.status()).thenReturn(status);
        when(grant.isEffectiveAt(NOW)).thenReturn(effective);
        when(grant.roleScope()).thenReturn(roleScope);
        when(grant.teamRoleId()).thenReturn(roleId);
        return grant;
    }
}
