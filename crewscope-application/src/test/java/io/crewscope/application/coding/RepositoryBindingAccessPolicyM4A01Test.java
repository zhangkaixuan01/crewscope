package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryBindingAccessPolicyM4A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-19T14:00:00Z");

    @Test
    void allowsBuiltInOwnerAndPlatformAdministratorButRejectsOrdinaryMember() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamInitialization initialization = TeamInitialization.create(actor, "Team", NOW);
        WorkProject project = WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("CODE"),
                "Code",
                initialization.team(),
                initialization.defaultWorkspace(),
                actor,
                NOW);
        WorkItemAccessPolicy workItemPolicy = mock(WorkItemAccessPolicy.class);
        WorkProjectRepository projects = mock(WorkProjectRepository.class);
        TeamRoleRepository roles = mock(TeamRoleRepository.class);
        MemberRoleRepository grants = mock(MemberRoleRepository.class);
        when(workItemPolicy.requireVisibleProject(any(), any(), any(), any())).thenReturn(project);
        when(workItemPolicy.requireVisibleTeamMember(any(), any(), any()))
                .thenReturn(initialization.ownerMember());
        when(projects.findById(organizationId, project.id())).thenReturn(Optional.of(project));
        when(roles.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(initialization.builtInRoles());
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of(initialization.ownerRole()));
        RepositoryBindingAccessPolicy policy =
                new RepositoryBindingAccessPolicy(workItemPolicy, projects, roles, grants);

        assertEquals(
                project,
                policy.requireAdministrator(
                        new TeamAccessContext(actor, false),
                        organizationId,
                        initialization.team().id(),
                        project.id(),
                        NOW));
        assertEquals(
                project,
                policy.requireAdministrator(
                        new TeamAccessContext(actor, true),
                        organizationId,
                        initialization.team().id(),
                        project.id(),
                        NOW));

        TeamRole memberRole = initialization.builtInRoles().stream()
                .filter(role -> role.isBuiltIn(BuiltInTeamRole.MEMBER))
                .findFirst()
                .orElseThrow();
        when(roles.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(List.of(memberRole));
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of());
        assertThrows(
                PolicyDeniedException.class,
                () -> policy.requireAdministrator(
                        new TeamAccessContext(actor, false),
                        organizationId,
                        initialization.team().id(),
                        project.id(),
                        NOW));

        Principal suspendedAdministrator = actor.transitionTo(PrincipalStatus.SUSPENDED, NOW);
        assertThrows(
                PolicyDeniedException.class,
                () -> policy.requireAdministrator(
                        new TeamAccessContext(suspendedAdministrator, true),
                        organizationId,
                        initialization.team().id(),
                        project.id(),
                        NOW));
        assertThrows(
                PolicyDeniedException.class,
                () -> policy.requireAdministrator(
                        new TeamAccessContext(actor, true),
                        OrganizationId.generate(),
                        initialization.team().id(),
                        project.id(),
                        NOW));
    }
}
