package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.crewscope.application.execution.TaskApprovalInterruptTokens;
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
import io.crewscope.domain.task.TaskExecutionWaitReason;
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
class MemberTaskCommandServiceM3A04Test extends MemberTaskCommandTestSupport {
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
    void resumeApprovesAConfirmationWaitWithTheReconstructableInterruptToken() {
        TaskExecution running = running(executionState.get());
        TaskAgentRuntimeSession session = TaskAgentRuntimeSession.initializeTask(
                taskState.get(), running, profile, executor, NOW);
        AgentRun active = AgentRun.start(AgentRunId.generate(), session, 1, executor, NOW);
        var token = TaskApprovalInterruptTokens.from(
                running.id(), active.id(), active.currentSegment().sequence());
        AgentInterrupt interrupt = AgentInterrupt.open(
                AgentInterruptId.generate(),
                active,
                AgentInterruptKind.APPROVAL,
                RuntimeContentHash.sha256(token.value()),
                executor,
                LATER);
        AgentRun interrupted = active.interrupt(
                interrupt, active.version(), executor, LATER);
        TaskExecution waiting = running.waitFor(
                TaskExecutionWaitReason.CONFIRMATION,
                running.version(),
                executor,
                LATER);
        executionState.set(waiting);
        when(runs.findByExecution(organizationId, waiting.id())).thenReturn(List.of(interrupted));
        when(interrupts.findPendingByRun(organizationId, interrupted.id()))
                .thenReturn(Optional.of(interrupt));

        var result = service.resume(
                context(owner, "approve-plan-1"),
                teamId,
                taskState.get().id(),
                waiting.id(),
                new RetryTaskCommand(waiting.version()));

        assertEquals(TaskExecutionStatus.READY,
                result.result().orElseThrow().targetExecution().status());
        ArgumentCaptor<AgentRunResumeCommand> command =
                ArgumentCaptor.forClass(AgentRunResumeCommand.class);
        verify(resumeService).resume(command.capture());
        assertEquals(token, command.getValue().interruptToken());
        assertEquals(interrupt.id(), command.getValue().interruptId());
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

    @Test
    void fivePauseReplaysReturnTheCommittedReceiptWithoutRepeatingAnySideEffect() {
        TaskExecution running = running(executionState.get());
        executionState.set(running);
        TeamCommandContext context = context(owner, "pause-five-replays");
        MemberTaskControlCommand command =
                new MemberTaskControlCommand(running.version(), "Pause once");

        var first = service.pause(context, teamId, taskState.get().id(), running.id(), command);
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(first.receipt()));
        for (int replay = 0; replay < 5; replay++) {
            var result = service.pause(context, teamId, taskState.get().id(), running.id(), command);
            assertTrue(result.replayed());
            assertEquals(first.receipt(), result.receipt());
        }

        verify(executions, times(1)).update(any());
        verify(events, times(1)).append(any());
        verify(taskEvents, times(1)).append(any(), any());
        verify(outbox, times(1)).enqueue(any());
        verify(receipts, times(1)).complete(any(), any(), any(), any());
        verify(resumeService, never()).resume(any());
    }

    @Test
    void fiveCancelReplaysDoNotRepeatExecutionTaskOrEventWrites() {
        TaskExecution ready = executionState.get();
        TeamCommandContext context = context(owner, "cancel-five-replays");
        MemberTaskControlCommand command =
                new MemberTaskControlCommand(ready.version(), "Cancel once");

        var first = service.cancel(context, teamId, taskState.get().id(), ready.id(), command);
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(first.receipt()));
        for (int replay = 0; replay < 5; replay++) {
            var result = service.cancel(context, teamId, taskState.get().id(), ready.id(), command);
            assertTrue(result.replayed());
            assertEquals(first.receipt(), result.receipt());
        }

        // READY cancellation writes REQUESTED then CANCELLED exactly once and closes Task once.
        verify(executions, times(2)).update(any());
        verify(tasks, times(1)).update(any());
        verify(events, times(1)).append(any());
        verify(taskEvents, times(1)).append(any(), any());
        verify(outbox, times(1)).enqueue(any());
        verify(receipts, times(1)).complete(any(), any(), any(), any());
        verify(resumeService, never()).resume(any());
    }

    @Test
    void fiveResumeReplaysDoNotRepeatAgentResumeOrDurableWrites() {
        TaskExecution running = running(executionState.get());
        TaskExecution requested = running.requestPause(
                "Pause before replay", running.version(), owner, LATER);
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
        AgentRun interrupted = active.interrupt(interrupt, active.version(), executor, LATER);
        TaskExecution paused = requested.acknowledgePaused(
                requested.version(), executor, LATER);
        executionState.set(paused);
        when(runs.findByExecution(organizationId, paused.id())).thenReturn(List.of(interrupted));
        when(interrupts.findPendingByRun(organizationId, interrupted.id()))
                .thenReturn(Optional.of(interrupt));
        TeamCommandContext context = context(owner, "resume-five-replays");
        RetryTaskCommand command = new RetryTaskCommand(paused.version());

        var first = service.resume(context, teamId, taskState.get().id(), paused.id(), command);
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(first.receipt()));
        for (int replay = 0; replay < 5; replay++) {
            var result = service.resume(context, teamId, taskState.get().id(), paused.id(), command);
            assertTrue(result.replayed());
            assertEquals(first.receipt(), result.receipt());
        }

        verify(resumeService, times(1)).resume(any());
        verify(executions, times(1)).update(any());
        verify(events, times(1)).append(any());
        verify(taskEvents, times(1)).append(any(), any());
        verify(outbox, times(1)).enqueue(any());
        verify(receipts, times(1)).complete(any(), any(), any(), any());
    }

}
