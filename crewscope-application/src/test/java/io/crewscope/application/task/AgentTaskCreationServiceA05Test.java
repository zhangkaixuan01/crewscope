package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.coding.BuildProfileCatalog;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.RepositoryBindingPreflightPort;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A05 assign-and-start: one delegation command may create the EXECUTOR responsibility and the Task
 * in one transaction — reusing an identical Executor, refusing a different one without explicit
 * release, re-checking RESPONSIBILITY_MANAGE inside the lock, and storing a TASK coordinate.
 */
class AgentTaskCreationServiceA05Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-25T08:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, workspaceId, projectId);
    private final Principal owner = user("Owner");
    private final Principal executor = personalAgent(owner);
    private final Principal otherExecutor = personalAgent(owner);
    private final WorkItem workItem = WorkItem.reconstitute(
            WorkItemId.generate(),
            scope,
            new WorkItemKey("CRW-501"),
            "Assign and start",
            WorkItemStatus.READY,
            4,
            AuditMetadata.createdBy(owner.id(), NOW));
    private final ResponsibilityAssignment ownerAssignment = assignment(
            ResponsibilityRole.OWNER, owner, Optional.of(TeamMemberId.generate()));
    private final ResponsibilityAssignment executorAssignment = assignment(
            ResponsibilityRole.EXECUTOR, executor, Optional.empty());
    private final ResponsibilityAssignment otherExecutorAssignment = assignment(
            ResponsibilityRole.EXECUTOR, otherExecutor, Optional.empty());
    private final AgentProfile profile = AgentProfile.reconstitute(
            AgentProfileId.generate(),
            WorkspaceScope.team(organizationId, teamId),
            workspaceId,
            executor.id(),
            Optional.of(TeamMemberId.generate()),
            AgentProfileType.PERSONAL,
            true,
            AgentProfileStatus.ACTIVE,
            2,
            AuditMetadata.createdBy(owner.id(), NOW));

    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final WorkItemRepository workItems = mock(WorkItemRepository.class);
    private final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final ConversationApplicationService conversations =
            mock(ConversationApplicationService.class);
    private final ProviderBindingResolver bindings = mock(ProviderBindingResolver.class);
    private final RepositoryBindingRepository repositoryBindings =
            mock(RepositoryBindingRepository.class);
    private final RepositoryBindingPreflightPort repositoryPreflight =
            mock(RepositoryBindingPreflightPort.class);
    private final BuildProfileCatalog buildProfiles = mock(BuildProfileCatalog.class);
    private final CodingTargetSnapshotRepository codingTargets =
            mock(CodingTargetSnapshotRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
    private final SafetyEnforcementOverlayRepository overlays =
            mock(SafetyEnforcementOverlayRepository.class);
    private final ConversationTaskLinkRepository links = mock(ConversationTaskLinkRepository.class);
    private final DomainEventStore events = mock(DomainEventStore.class);
    private final ConversationEventRepository conversationEvents =
            mock(ConversationEventRepository.class);
    private final TaskEventRepository taskEvents = mock(TaskEventRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };
    private final TaskCreationPolicySpec policySpec = new TaskCreationPolicySpec(
            new PolicyPackReference(PolicyPackId.generate(), 1),
            Set.of(ExecutionCapability.PLAN),
            Set.of("fixture_execute"),
            new PolicyBudget(10_000, 8, 16, 300),
            3,
            TaskExecutionPriority.NORMAL);

    private AgentTaskCreationService service;

    @BeforeEach
    void setUp() {
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(workItems.findById(organizationId, workItem.id())).thenReturn(Optional.of(workItem));
        when(profiles.findById(organizationId, profile.id())).thenReturn(Optional.of(profile));
        when(principals.findById(organizationId, executor.id()))
                .thenReturn(Optional.of(executor));
        when(tasks.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(policies.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(overlays.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignments.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AgentTaskCreationService(
                accessPolicy,
                workItems,
                assignments,
                principals,
                profiles,
                conversations,
                bindings,
                repositoryBindings,
                repositoryPreflight,
                buildProfiles,
                codingTargets,
                tasks,
                executions,
                policies,
                overlays,
                links,
                events,
                conversationEvents,
                taskEvents,
                outbox,
                receipts,
                transactions,
                () -> NOW,
                policySpec,
                null,
                null,
                new ResponsibilityAssignmentService(assignments, transactions, () -> NOW));
    }

    @Test
    void assignAndStartCreatesExecutorAndTaskInOneCommand() {
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment));

        CommandExecution<AgentTaskCreationResult> execution = service.create(
                context(owner, "assign-and-start-1"),
                teamId, projectId, workItem.id(), commandWithAssignment());

        AgentTaskCreationResult result = execution.result().orElseThrow();
        assertFalse(execution.replayed());

        ArgumentCaptor<ResponsibilityAssignment> assignmentCaptor =
                ArgumentCaptor.forClass(ResponsibilityAssignment.class);
        verify(assignments).create(assignmentCaptor.capture());
        assertEquals(ResponsibilityRole.EXECUTOR, assignmentCaptor.getValue().role());
        assertEquals(executor.id(), assignmentCaptor.getValue().actorPrincipalId());

        verify(accessPolicy).requirePermission(
                any(), eq(organizationId), eq(teamId), eq(projectId), eq(workItem.id()),
                eq(TeamPermission.RESPONSIBILITY_MANAGE), eq(NOW),
                eq("manage this WorkItem's responsibilities"));

        ArgumentCaptor<DomainEventEnvelope<?>> eventCaptor =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, times(2)).append(eventCaptor.capture());
        assertEquals("WORK_ITEM_EXECUTOR_ASSIGNED",
                eventCaptor.getAllValues().get(0).eventType().value());
        assertEquals("TASK_DELEGATED_TO_AGENT",
                eventCaptor.getAllValues().get(1).eventType().value());
        verify(outbox, times(2)).enqueue(any());

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(tasks).create(taskCaptor.capture());
        verify(tasks).update(any());
        assertEquals(2, result.task().responsibilitySnapshot().entries().size());
        assertEquals(executor.id(), result.task().responsibilitySnapshot()
                .byRole(ResponsibilityRole.EXECUTOR).get(0).principalId());

        ArgumentCaptor<CommandResult> resultCaptor = ArgumentCaptor.forClass(CommandResult.class);
        verify(receipts).saveResult(resultCaptor.capture());
        CommandResult coordinate = resultCaptor.getValue();
        assertEquals(CommandResult.ResourceType.TASK, coordinate.resourceType());
        assertEquals(result.task().id().value(), coordinate.resourceId());
        assertEquals(Optional.of(projectId), coordinate.projectId());
        assertEquals("DELEGATE_WORK_ITEM_TO_AGENT", coordinate.commandType());
    }

    @Test
    void identicalActiveExecutorIsReusedWithoutAResponsibilityWrite() {
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, executorAssignment));

        CommandExecution<AgentTaskCreationResult> execution = service.create(
                context(owner, "assign-and-start-reuse"),
                teamId, projectId, workItem.id(), commandWithAssignment());

        assertFalse(execution.replayed());
        verify(assignments, never()).create(any());
        verify(accessPolicy, never()).requirePermission(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(events, times(1)).append(any());
        assertEquals("TASK_DELEGATED_TO_AGENT", lastEvent().eventType().value());
    }

    @Test
    void differentActiveExecutorIsAnExplicitReleaseConflict() {
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, otherExecutorAssignment));

        DomainValidationException denied = assertThrows(
                DomainValidationException.class,
                () -> service.create(
                        context(owner, "assign-and-start-conflict"),
                        teamId, projectId, workItem.id(), commandWithAssignment()));

        assertEquals("agentTask.executorAssignment", denied.error().details().get("field"));
        assertTrue(denied.getMessage().contains("release it explicitly first"));
        verify(assignments, never()).create(any());
        verifyNoInteractions(tasks, executions, policies, overlays);
    }

    @Test
    void assignmentWithoutResponsibilityManageIsDeniedBeforeAnyWrite() {
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment));
        when(accessPolicy.requirePermission(
                        any(), eq(organizationId), eq(teamId), eq(projectId), eq(workItem.id()),
                        eq(TeamPermission.RESPONSIBILITY_MANAGE), eq(NOW), any()))
                .thenThrow(new PolicyDeniedException("manage this WorkItem's responsibilities"));

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(
                        context(owner, "assign-and-start-denied"),
                        teamId, projectId, workItem.id(), commandWithAssignment()));

        verify(assignments, never()).create(any());
        verifyNoInteractions(tasks, executions, policies, overlays);
    }

    @Test
    void replayDoesNotAssignOrDelegateTwice() {
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment));
        CommandReservation replay = CommandReservation.replay(new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID()));
        when(receipts.reserve(any()))
                .thenReturn(CommandReservation.newlyAcquired())
                .thenReturn(replay);
        TeamCommandContext context = context(owner, "assign-and-start-replay");

        service.create(context, teamId, projectId, workItem.id(), commandWithAssignment());
        CommandExecution<AgentTaskCreationResult> second =
                service.create(context, teamId, projectId, workItem.id(), commandWithAssignment());

        assertTrue(second.replayed());
        verify(assignments, times(1)).create(any());
        verify(tasks, times(1)).create(any());
        verify(events, times(2)).append(any());
    }

    @Test
    void requestHashBindsTheAssignmentInstruction() {
        // The executor is already assigned, so both payloads are executable and only the
        // assignment instruction differs — the same key must not silently cover both.
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, executorAssignment));

        service.create(context(owner, "assign-hash-key"), teamId, projectId, workItem.id(),
                command());
        service.create(context(owner, "assign-hash-key"), teamId, projectId, workItem.id(),
                commandWithAssignment());

        ArgumentCaptor<CommandReservationRequest> reservations =
                ArgumentCaptor.forClass(CommandReservationRequest.class);
        verify(receipts, times(2)).reserve(reservations.capture());
        assertFalse(reservations.getAllValues().get(0).requestHash()
                .equals(reservations.getAllValues().get(1).requestHash()));
    }

    @Test
    void assignmentInstructionMustTargetTheExecutionSelection() {
        AgentProfileId differentProfile = AgentProfileId.generate();
        assertThrows(
                IllegalArgumentException.class,
                () -> new CreateAgentTaskCommand(
                        command().brief(),
                        command().executorAgentProfileId(),
                        Optional.empty(),
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        Optional.of(new ExecutorAssignmentInstruction(differentProfile)),
                        command().expectedWorkItemVersion()));
    }

    private CreateAgentTaskCommand command() {
        return new CreateAgentTaskCommand(
                new TaskBrief(
                        "Assign and start in one command",
                        List.of("Executor assignment and Task commit together")),
                profile.id(),
                Optional.empty(),
                Set.of(),
                workItem.version());
    }

    private CreateAgentTaskCommand commandWithAssignment() {
        return new CreateAgentTaskCommand(
                command().brief(),
                command().executorAgentProfileId(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.of(new ExecutorAssignmentInstruction(profile.id())),
                command().expectedWorkItemVersion());
    }

    private DomainEventEnvelope<?> lastEvent() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, times(1)).append(captor.capture());
        return captor.getValue();
    }

    private TeamCommandContext context(Principal actor, String key) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                new IdempotencyKey(key),
                UUID.randomUUID(),
                Optional.empty());
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

    private Principal personalAgent(Principal ownerPrincipal) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, teamId),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(ownerPrincipal.id()),
                "Owner agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                NOW);
    }

    private ResponsibilityAssignment assignment(
            ResponsibilityRole role, Principal actor, Optional<TeamMemberId> memberId) {
        return ResponsibilityAssignment.reconstitute(
                ResponsibilityAssignmentId.generate(),
                scope,
                workItem.id(),
                role,
                actor.id(),
                actor.type(),
                memberId,
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
