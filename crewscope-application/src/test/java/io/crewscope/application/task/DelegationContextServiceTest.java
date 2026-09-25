package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.ProjectExecutionDefaultsApplicationService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentView;
import io.crewscope.application.responsibility.ResponsibilityQueryService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.ProjectExecutionDefaults;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A05 delegation-context: one read assembles the chain, the member-visible Agent candidates with
 * conflict marking, the A04 defaults, the in-flight execution fact and the authority flags — the
 * form never re-derives truth from several pages.
 */
class DelegationContextServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-25T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, workspaceId, projectId);
    private final Principal owner = user("Owner");
    private final Principal personalAgent = agent("Owner agent", PrincipalType.PERSONAL_AGENT);
    private final Principal teamAgent = agent("Team agent", PrincipalType.TEAM_AGENT);
    private final TeamMemberId ownerMemberId = TeamMemberId.generate();
    private final TeamMember ownerMember = TeamMember.join(
            ownerMemberId, new TeamScope(organizationId, teamId), owner,
            TeamJoinMethod.SCIM, NOW);
    private final WorkItem workItem = WorkItem.reconstitute(
            WorkItemId.generate(),
            scope,
            new WorkItemKey("CRW-601"),
            "Delegation context",
            WorkItemStatus.READY,
            4,
            AuditMetadata.createdBy(owner.id(), NOW));
    private final ResponsibilityAssignment ownerAssignment = assignment(
            ResponsibilityRole.OWNER, owner);
    private final ResponsibilityAssignment executorAssignment = assignment(
            ResponsibilityRole.EXECUTOR, personalAgent);
    private final AgentProfile personalProfile = profile(
            personalAgent, AgentProfileType.PERSONAL, AgentProfileStatus.ACTIVE);
    private final AgentProfile teamProfile = profile(
            teamAgent, AgentProfileType.TEAM, AgentProfileStatus.ACTIVE);
    private final ProjectExecutionDefaults defaults = new ProjectExecutionDefaults(
            organizationId, teamId, workspaceId, projectId, 3,
            Optional.of(RepositoryBindingId.generate()), Optional.of(2L),
            Optional.of(new RepositoryBranchName("main")),
            Optional.of(new BuildProfileReference("maven-java-17", 1,
                    new io.crewscope.domain.task.TaskFactHash(
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))),
            Optional.of(personalProfile.id()), Optional.of(4L));

    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final ResponsibilityQueryService responsibilityQuery =
            mock(ResponsibilityQueryService.class);
    private final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
    private final ProjectExecutionDefaultsApplicationService defaultsService =
            mock(ProjectExecutionDefaultsApplicationService.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    private DelegationContextService service;

    @BeforeEach
    void setUp() {
        when(accessPolicy.requireVisibleWorkItem(
                any(), eq(organizationId), eq(teamId), eq(projectId), eq(workItem.id())))
                .thenReturn(workItem);
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment));
        when(responsibilityQuery.listActive(
                any(), eq(organizationId), eq(teamId), eq(projectId), eq(workItem.id())))
                .thenAnswer(invocation -> {
                    List<ResponsibilityAssignment> active = assignments
                            .findActiveByWorkItem(organizationId, workItem.id());
                    return active.stream()
                            .map(value -> new ResponsibilityAssignmentView(
                                    value, displayName(value.actorPrincipalId()), Optional.empty()))
                            .toList();
                });
        when(profiles.findVisibleToMember(
                eq(organizationId), eq(teamId), eq(ownerMemberId), anyInt(), anyInt()))
                .thenReturn(List.of(personalProfile, teamProfile));
        when(memberships.findByTeam(organizationId, teamId))
                .thenReturn(List.of(ownerMember));
        when(principals.findById(organizationId, personalAgent.id()))
                .thenReturn(Optional.of(personalAgent));
        when(principals.findById(organizationId, teamAgent.id()))
                .thenReturn(Optional.of(teamAgent));
        when(defaultsService.get(any(), eq(organizationId), eq(teamId), eq(projectId)))
                .thenReturn(defaults);
        when(tasks.findByWorkItem(organizationId, workItem.id())).thenReturn(List.of());
        when(accessPolicy.hasPermission(
                any(), eq(organizationId), eq(teamId), eq(projectId), any(), eq(NOW)))
                .thenReturn(true);
        service = new DelegationContextService(
                accessPolicy,
                responsibilityQuery,
                assignments,
                profiles,
                principals,
                memberships,
                defaultsService,
                tasks,
                executions,
                transactions,
                () -> NOW);
    }

    @Test
    void unassignedWorkItemExposesEveryVisibleAgentAsAvailable() {
        DelegationContext context = service.getContext(
                new TeamAccessContext(owner, false), teamId, projectId, workItem.id());

        assertEquals(2, context.candidates().size());
        assertTrue(context.candidates().stream()
                .allMatch(value -> value.state().equals(DelegationContext.STATE_AVAILABLE)));
        assertFalse(context.activeExecution());
        assertTrue(context.permissions().canAssignResponsibility());
        assertTrue(context.permissions().canDelegate());
        assertEquals(workItem.title(), context.workItem().title());
        assertEquals(4, context.workItem().version());
    }

    @Test
    void identicalActiveExecutorIsAssignedWhileOthersCarryTheConflict() {
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, executorAssignment));

        DelegationContext context = service.getContext(
                new TeamAccessContext(owner, false), teamId, projectId, workItem.id());

        DelegationContext.AgentCandidate assigned = context.candidates().stream()
                .filter(value -> value.agentProfileId().equals(personalProfile.id()))
                .findFirst().orElseThrow();
        DelegationContext.AgentCandidate conflicting = context.candidates().stream()
                .filter(value -> value.agentProfileId().equals(teamProfile.id()))
                .findFirst().orElseThrow();
        assertEquals(DelegationContext.STATE_ASSIGNED, assigned.state());
        assertEquals(DelegationContext.STATE_EXECUTOR_CONFLICT, conflicting.state());
        assertTrue(conflicting.reason().isPresent());
        assertTrue(context.responsibilities().stream()
                .anyMatch(value -> value.role() == ResponsibilityRole.EXECUTOR
                        && value.actorPrincipalId().equals(personalAgent.id())));
    }

    @Test
    void disabledAgentProfileIsNotSelectable() {
        AgentProfile disabled = profile(
                teamAgent, AgentProfileType.TEAM, AgentProfileStatus.DISABLED);
        when(profiles.findVisibleToMember(
                eq(organizationId), eq(teamId), eq(ownerMemberId), anyInt(), anyInt()))
                .thenReturn(List.of(personalProfile, disabled));

        DelegationContext context = service.getContext(
                new TeamAccessContext(owner, false), teamId, projectId, workItem.id());

        DelegationContext.AgentCandidate candidate = context.candidates().stream()
                .filter(value -> value.agentProfileId().equals(disabled.id()))
                .findFirst().orElseThrow();
        assertEquals(DelegationContext.STATE_AGENT_DISABLED, candidate.state());
        assertTrue(candidate.reason().isPresent());
    }

    @Test
    void missingAgentPrincipalIsNotSelectable() {
        when(principals.findById(organizationId, teamAgent.id()))
                .thenReturn(Optional.empty());

        DelegationContext context = service.getContext(
                new TeamAccessContext(owner, false), teamId, projectId, workItem.id());

        DelegationContext.AgentCandidate candidate = context.candidates().stream()
                .filter(value -> value.agentProfileId().equals(teamProfile.id()))
                .findFirst().orElseThrow();
        assertEquals(DelegationContext.STATE_PRINCIPAL_INACTIVE, candidate.state());
        assertTrue(candidate.reason().isPresent());
    }

    @Test
    void waitingCurrentExecutionReportsAnActiveExecution() {
        TaskExecution execution = mock(TaskExecution.class);
        TaskExecutionId executionId = TaskExecutionId.generate();
        io.crewscope.domain.task.Task running = taskWithExecution(executionId);
        when(tasks.findByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(running));
        when(execution.status()).thenReturn(TaskExecutionStatus.WAITING);
        when(executions.findById(organizationId, executionId))
                .thenReturn(Optional.of(execution));

        DelegationContext context = service.getContext(
                new TeamAccessContext(owner, false), teamId, projectId, workItem.id());

        assertTrue(context.activeExecution());
    }

    @Test
    void completedCurrentExecutionIsNoLongerActive() {
        TaskExecution execution = mock(TaskExecution.class);
        TaskExecutionId executionId = TaskExecutionId.generate();
        io.crewscope.domain.task.Task finished = taskWithExecution(executionId);
        when(tasks.findByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(finished));
        when(execution.status()).thenReturn(TaskExecutionStatus.COMPLETED);
        when(executions.findById(organizationId, executionId))
                .thenReturn(Optional.of(execution));

        DelegationContext context = service.getContext(
                new TeamAccessContext(owner, false), teamId, projectId, workItem.id());

        assertFalse(context.activeExecution());
    }

    @Test
    void memberWithoutResponsibilityHasNoDelegationAuthority() {
        Principal bystander = user("Bystander");
        TeamMember member = TeamMember.join(
                TeamMemberId.generate(), new TeamScope(organizationId, teamId),
                bystander, TeamJoinMethod.SCIM, NOW);
        when(memberships.findByTeam(organizationId, teamId))
                .thenReturn(List.of(ownerMember, member));

        DelegationContext context = service.getContext(
                new TeamAccessContext(bystander, false), teamId, projectId, workItem.id());

        assertTrue(context.permissions().canAssignResponsibility());
        assertFalse(context.permissions().canDelegate());
    }

    @Test
    void nonMemberWithoutPlatformAdministrationIsDenied() {
        when(memberships.findByTeam(organizationId, teamId)).thenReturn(List.of());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.getContext(
                        new TeamAccessContext(owner, false),
                        teamId, projectId, workItem.id()));
    }

    @Test
    void defaultsSnapshotMirrorsTheA04ResolverOutput() {
        DelegationContext context = service.getContext(
                new TeamAccessContext(owner, false), teamId, projectId, workItem.id());

        DelegationContext.DefaultsSnapshot snapshot = context.defaults();
        assertEquals(3, snapshot.version());
        assertEquals(defaults.repositoryBindingId(), snapshot.repositoryBindingId());
        assertEquals(defaults.branch(), snapshot.branch());
        assertEquals(defaults.buildProfile(), snapshot.buildProfile());
        assertEquals(Optional.of(personalProfile.id()), snapshot.agentProfileId());
        assertEquals(Optional.of(4L), snapshot.agentProfileRevision());
    }

    private io.crewscope.domain.task.Task taskWithExecution(TaskExecutionId executionId) {
        io.crewscope.domain.task.Task task = mock(io.crewscope.domain.task.Task.class);
        when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
        return task;
    }

    private String displayName(PrincipalId principalId) {
        if (principalId.equals(owner.id())) {
            return "Owner";
        }
        if (principalId.equals(personalAgent.id())) {
            return "Owner agent";
        }
        if (principalId.equals(teamAgent.id())) {
            return "Team agent";
        }
        return "Unknown";
    }

    private Principal user(String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
    }

    private Principal agent(String name, PrincipalType type) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                type,
                Optional.of(owner.id()),
                name,
                Optional.empty(),
                type == PrincipalType.TEAM_AGENT
                        ? PrincipalVisibility.TEAM : PrincipalVisibility.PRIVATE,
                NOW);
    }

    private AgentProfile profile(
            Principal agentPrincipal, AgentProfileType type, AgentProfileStatus status) {
        return AgentProfile.reconstitute(
                AgentProfileId.generate(),
                WorkspaceScope.team(organizationId, teamId),
                workspaceId,
                agentPrincipal.id(),
                type == AgentProfileType.PERSONAL
                        ? Optional.of(ownerMemberId) : Optional.empty(),
                type,
                type == AgentProfileType.PERSONAL,
                status,
                2,
                AuditMetadata.createdBy(owner.id(), NOW));
    }

    private ResponsibilityAssignment assignment(
            ResponsibilityRole role, Principal actor) {
        return ResponsibilityAssignment.reconstitute(
                ResponsibilityAssignmentId.generate(),
                scope,
                workItem.id(),
                role,
                actor.id(),
                actor.type(),
                role == ResponsibilityRole.OWNER ? Optional.of(ownerMemberId) : Optional.empty(),
                ResponsibilityAssignmentStatus.ACTIVE,
                owner.id(),
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(owner.id(), NOW));
    }
}
