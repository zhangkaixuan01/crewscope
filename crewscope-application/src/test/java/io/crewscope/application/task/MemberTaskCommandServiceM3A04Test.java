package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.execution.AgentRunResumeCommand;
import io.crewscope.application.execution.DurableAgentRunResumeService;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
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
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.task.event.MemberTaskCommandAccepted;
import io.crewscope.domain.team.TeamMemberId;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Member authority, convergence, retry and Resume contract for M3-A04. */
class MemberTaskCommandServiceM3A04Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T10:00:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-15T10:01:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkspaceId workspaceId = WorkspaceId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, workspaceId, projectId);
    private final Principal owner = user("Owner");
    private final Principal executor = personalAgent(owner);
    private final Principal observer = user("Observer");
    private final WorkItem workItem = WorkItem.reconstitute(
            WorkItemId.generate(),
            scope,
            new WorkItemKey("CRW-304"),
            "Durable member controls",
            WorkItemStatus.READY,
            4,
            AuditMetadata.createdBy(owner.id(), NOW));
    private final ResponsibilityAssignment ownerAssignment = assignment(
            ResponsibilityRole.OWNER, owner, Optional.of(TeamMemberId.generate()));
    private final ResponsibilityAssignment executorAssignment = assignment(
            ResponsibilityRole.EXECUTOR, executor, Optional.empty());
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
    private final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final ProviderBindingResolver bindings = mock(ProviderBindingResolver.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
    private final SafetyEnforcementOverlayRepository overlays =
            mock(SafetyEnforcementOverlayRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentInterruptRepository interrupts = mock(AgentInterruptRepository.class);
    private final DurableAgentRunResumeService resumeService =
            mock(DurableAgentRunResumeService.class);
    private final DomainEventStore events = mock(DomainEventStore.class);
    private final TaskEventRepository taskEvents = mock(TaskEventRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
    private final AtomicReference<Task> taskState = new AtomicReference<>();
    private final AtomicReference<TaskExecution> executionState = new AtomicReference<>();
    private final AtomicReference<PolicySnapshot> policyState = new AtomicReference<>();
    private MemberTaskCommandService service;

    @BeforeEach
    void setUp() {
        Fixture fixture = fixture(3);
        taskState.set(fixture.task());
        executionState.set(fixture.execution());
        policyState.set(fixture.policy());
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(accessPolicy.requireVisibleWorkItem(
                        any(), any(), any(), any(), any()))
                .thenReturn(workItem);
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment, executorAssignment));
        when(tasks.findById(any(), any()))
                .thenAnswer(ignored -> Optional.of(taskState.get()));
        when(tasks.findByIdForUpdate(any(), any()))
                .thenAnswer(ignored -> Optional.of(taskState.get()));
        when(tasks.update(any())).thenAnswer(invocation -> {
            Task value = invocation.getArgument(0);
            taskState.set(value);
            return value;
        });
        when(executions.findById(any(), any()))
                .thenAnswer(ignored -> Optional.of(executionState.get()));
        when(executions.findByIdForUpdate(any(), any()))
                .thenAnswer(ignored -> Optional.of(executionState.get()));
        when(executions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executions.update(any())).thenAnswer(invocation -> {
            TaskExecution value = invocation.getArgument(0);
            executionState.set(value);
            return value;
        });
        when(policies.findById(any(), any()))
                .thenAnswer(ignored -> Optional.of(policyState.get()));
        when(policies.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(overlays.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(principals.findById(organizationId, executor.id())).thenReturn(Optional.of(executor));
        when(profiles.findById(organizationId, profile.id())).thenReturn(Optional.of(profile));
        service = new MemberTaskCommandService(
                accessPolicy,
                assignments,
                principals,
                profiles,
                bindings,
                tasks,
                executions,
                policies,
                overlays,
                runs,
                interrupts,
                resumeService,
                events,
                taskEvents,
                outbox,
                receipts,
                new DirectTransactions(),
                () -> LATER);
    }

    @Test
    void ownerRequestsPauseAndCommitsOneSafeAuditEvent() {
        TaskExecution running = running(executionState.get());
        executionState.set(running);

        var result = service.pause(
                context(owner, "pause-1"),
                teamId,
                taskState.get().id(),
                running.id(),
                new MemberTaskControlCommand(running.version(), "Pause for review"));

        assertEquals(TaskExecutionStatus.PAUSE_REQUESTED,
                result.result().orElseThrow().targetExecution().status());
        ArgumentCaptor<DomainEventEnvelope<?>> event = eventCaptor();
        verify(events).append(event.capture());
        verify(taskEvents).append(any(), eq(event.getValue()));
        MemberTaskCommandAccepted payload =
                (MemberTaskCommandAccepted) event.getValue().payload();
        assertEquals("PAUSE", payload.operation());
        assertEquals("PAUSE_REQUESTED", payload.executionStatus());
        verify(outbox).enqueue(any());
        verify(receipts).complete(any(), any(), any(), any());
    }

    @Test
    void cancelConvergesAnUnownedAttemptImmediatelyAndClosesTheTask() {
        TaskExecution ready = executionState.get();

        var result = service.cancel(
                context(owner, "cancel-ready"),
                teamId,
                taskState.get().id(),
                ready.id(),
                new MemberTaskControlCommand(ready.version(), "No longer required"));

        MemberTaskCommandResult committed = result.result().orElseThrow();
        assertEquals(TaskExecutionStatus.CANCELLED, committed.targetExecution().status());
        assertEquals(TaskStatus.CANCELLED, committed.task().status());
        assertEquals("No longer required", committed.task().cancellation().orElseThrow().reason());
    }

    @Test
    void resumeResolvesTheExactPauseInterruptAndRequeuesTheSameAttempt() {
        TaskExecution running = running(executionState.get());
        TaskExecution requested = running.requestPause(
                "Pause before continuing", running.version(), owner, LATER);
        UUID requestId = TaskControlRequestIds.from(
                requested.id(), requested.controlRequest().orElseThrow());
        TaskAgentRuntimeSession session = TaskAgentRuntimeSession.initializeTask(
                taskState.get(), running, profile, executor, NOW);
        AgentRun active = AgentRun.start(AgentRunId.generate(), session, 1, executor, NOW);
        AgentInterrupt interrupt = AgentInterrupt.open(
                AgentInterruptId.generate(),
                active,
                AgentInterruptKind.PAUSE,
                RuntimeContentHash.sha256(requestId.toString()),
                executor,
                LATER);
        AgentRun interrupted = active.interrupt(
                interrupt, active.version(), executor, LATER);
        TaskExecution paused = requested.acknowledgePaused(
                requested.version(), executor, LATER);
        executionState.set(paused);
        when(runs.findByExecution(organizationId, paused.id())).thenReturn(List.of(interrupted));
        when(interrupts.findPendingByRun(organizationId, interrupted.id()))
                .thenReturn(Optional.of(interrupt));

        var result = service.resume(
                context(owner, "resume-1"),
                teamId,
                taskState.get().id(),
                paused.id(),
                new RetryTaskCommand(paused.version()));

        assertEquals(TaskExecutionStatus.READY,
                result.result().orElseThrow().targetExecution().status());
        ArgumentCaptor<AgentRunResumeCommand> command =
                ArgumentCaptor.forClass(AgentRunResumeCommand.class);
        verify(resumeService).resume(command.capture());
        assertEquals(requestId.toString(), command.getValue().interruptToken().value());
        assertEquals(interrupted.id(), command.getValue().agentRunId());
    }

    @Test
    void retryCreatesAReadySuccessorAndRevalidatesPinnedAuthorization() {
        TaskExecution failed = failed(executionState.get(), true);
        executionState.set(failed);

        var result = service.retry(
                context(owner, "retry-1"),
                teamId,
                taskState.get().id(),
                failed.id(),
                new RetryTaskCommand(failed.version()));

        MemberTaskCommandResult committed = result.result().orElseThrow();
        TaskExecution successor = committed.successorExecution().orElseThrow();
        assertEquals(2, successor.attempt());
        assertEquals(Optional.of(failed.id()), successor.parentExecutionId());
        assertEquals(TaskExecutionStatus.READY, successor.status());
        assertEquals(TaskStatus.ACTIVE, committed.task().status());
        assertEquals(Optional.of(successor.id()), committed.task().currentExecutionId());
        verify(principals).findById(organizationId, executor.id());
        verify(profiles).findById(organizationId, profile.id());
    }

    @Test
    void retryRejectsExhaustedAttemptsAndChangedExecutorResponsibility() {
        Fixture exhaustedFixture = fixture(1);
        taskState.set(exhaustedFixture.task());
        executionState.set(failed(exhaustedFixture.execution(), true));
        policyState.set(exhaustedFixture.policy());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> service.retry(
                        context(owner, "retry-exhausted"),
                        teamId,
                        taskState.get().id(),
                        executionState.get().id(),
                        new RetryTaskCommand(executionState.get().version())));

        Fixture retryableFixture = fixture(3);
        taskState.set(retryableFixture.task());
        executionState.set(failed(retryableFixture.execution(), true));
        policyState.set(retryableFixture.policy());
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.of(ownerAssignment));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.retry(
                        context(owner, "retry-revoked"),
                        teamId,
                        taskState.get().id(),
                        executionState.get().id(),
                        new RetryTaskCommand(executionState.get().version())));
    }

    @Test
    void unauthorizedMemberAndStaleVersionFailBeforeMutation() {
        TaskExecution running = running(executionState.get());
        executionState.set(running);

        assertThrows(
                PolicyDeniedException.class,
                () -> service.pause(
                        context(observer, "pause-denied"),
                        teamId,
                        taskState.get().id(),
                        running.id(),
                        new MemberTaskControlCommand(running.version(), "Try to pause")));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.pause(
                        context(owner, "pause-stale"),
                        teamId,
                        taskState.get().id(),
                        running.id(),
                        new MemberTaskControlCommand(running.version() - 1, "Stale pause")));
        verify(events, never()).append(any());
    }

    @Test
    void exactReplayDoesNotRepeatControlSideEffects() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 7, UUID.randomUUID());
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(receipt));

        var replay = service.pause(
                context(owner, "pause-replay"),
                teamId,
                taskState.get().id(),
                executionState.get().id(),
                new MemberTaskControlCommand(executionState.get().version(), "Pause once"));

        assertTrue(replay.replayed());
        assertEquals(receipt, replay.receipt());
        verify(executions, never()).update(any());
        verify(events, never()).append(any());
    }

    private Fixture fixture(int maxAttempts) {
        Task task = Task.create(
                TaskId.generate(),
                workItem,
                TaskSource.fromWorkItem(workItem),
                new TaskBrief("Control a durable Task", List.of("Pause and resume safely")),
                TaskResponsibilitySnapshot.capture(
                        workItem, List.of(ownerAssignment, executorAssignment), NOW),
                owner,
                NOW);
        TaskExecution created = TaskExecution.firstAttempt(
                TaskExecutionId.generate(),
                task,
                maxAttempts,
                TaskExecutionPriority.NORMAL,
                NOW,
                owner,
                NOW);
        PolicySnapshot policy = PolicySnapshot.initial(
                PolicySnapshotId.generate(),
                task,
                created,
                executor,
                new PolicyPackReference(PolicyPackId.generate(), 1),
                profile.id(),
                profile.version(),
                Set.of(ExecutionCapability.PLAN),
                Set.of("fixture.execute"),
                Set.of(),
                new PolicyBudget(10_000, 8, 16, 300),
                owner,
                NOW);
        SafetyEnforcementOverlay overlay = SafetyEnforcementOverlay.unrestricted(
                SafetyEnforcementOverlayId.generate(), task, created, owner, NOW);
        TaskExecution planned = created.initializePlanningContext(
                policy, overlay, created.version(), owner, NOW);
        TaskExecution ready = planned.markReady(planned.version(), owner, NOW);
        Task active = task.switchCurrentExecution(
                Optional.empty(), ready.id(), task.version(), owner, NOW);
        return new Fixture(active, ready, policy);
    }

    private TaskExecution running(TaskExecution ready) {
        TaskExecution claimed = ready.claim(ready.version(), executor, LATER);
        TaskExecution preparing = claimed.beginPreparing(claimed.version(), executor, LATER);
        return preparing.beginRunning(preparing.version(), executor, LATER);
    }

    private TaskExecution failed(TaskExecution ready, boolean retryable) {
        TaskExecution running = running(ready);
        return running.fail(
                new TaskExecutionFailure(
                        retryable
                                ? TaskExecutionFailureClass.TRANSIENT
                                : TaskExecutionFailureClass.VALIDATION,
                        retryable ? "TRANSIENT_FAILURE" : "VALIDATION_FAILURE"),
                running.version(),
                executor,
                LATER);
    }

    private TeamCommandContext context(Principal actor, String key) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                IdempotencyKey.from("m3-a04/" + key),
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
                "Owner Agent",
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<DomainEventEnvelope<?>> eventCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(DomainEventEnvelope.class);
    }

    private record Fixture(Task task, TaskExecution execution, PolicySnapshot policy) {}

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
