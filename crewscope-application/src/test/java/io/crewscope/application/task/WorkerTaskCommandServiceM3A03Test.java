package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.task.event.WorkerTaskCommandAccepted;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Trusted identity, idempotency and audit contract for the M3-A03 Worker Port. */
class WorkerTaskCommandServiceM3A03Test {

    private final TaskClaimScheduler claims = mock(TaskClaimScheduler.class);
    private final TaskExecutionLeaseCoordinator leases = mock(TaskExecutionLeaseCoordinator.class);
    private final DomainEventStore events = mock(DomainEventStore.class);
    private final TaskEventRepository taskEvents = mock(TaskEventRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
    private final UtcTimestamp now = UtcTimestamp.parse("2026-08-15T08:00:00.123456Z");
    private final TaskTokenExecutionContext authorization = authorization();
    private final Principal workerActor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(authorization.scope().workItemScope().organizationId()),
            PrincipalType.SERVICE,
            Optional.empty(),
            "Task Worker",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            now);
    private final WorkerTaskCommandService service = new WorkerTaskCommandService(
            claims,
            leases,
            events,
            taskEvents,
            outbox,
            receipts,
            new DirectTransactions(),
            () -> now,
            workerActor);

    @BeforeEach
    void acquireCommands() {
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
    }

    @Test
    void delegatesClaimAndCommitsTheCompleteFencedMutationFamily() {
        TaskClaimBatchResult batch = new TaskClaimBatchResult(List.of(), 0, 0, 0, 0);
        when(claims.claim(3)).thenReturn(batch);
        TaskExecution preparing = execution(2);
        TaskExecution running = execution(3);
        TaskExecution progressed = execution(4);
        TaskExecution completed = execution(5);
        ExecutionLease runLease = lease(1);
        ExecutionLease heartbeatLease = lease(2);
        ExecutionLease releasedLease = lease(3);
        when(leases.beginPreparing(any())).thenReturn(preparing);
        when(leases.beginRun(any())).thenReturn(new LeaseMutationResult(running, runLease));
        when(leases.heartbeat(any())).thenReturn(heartbeatLease);
        when(leases.updateOwned(any(), any())).thenReturn(progressed);
        when(leases.release(any())).thenReturn(new LeaseMutationResult(completed, releasedLease));

        assertEquals(batch, service.claim(3));
        service.prepare(context("prepare"), new WorkerPrepareCommand(1));
        service.start(context("start"), new WorkerTransitionCommand(2, 0));
        service.heartbeat(context("heartbeat"), new WorkerHeartbeatCommand(1));
        service.progress(context("progress"), new WorkerProgressCommand(
                3, "完成安全检查", Optional.of(40)));
        service.complete(context("complete"), new WorkerTransitionCommand(4, 2));

        ArgumentCaptor<LeaseExecutionCommand> prepare =
                ArgumentCaptor.forClass(LeaseExecutionCommand.class);
        verify(leases).beginPreparing(prepare.capture());
        assertOwnership(prepare.getValue().scope());
        ArgumentCaptor<LeaseTransitionCommand> start =
                ArgumentCaptor.forClass(LeaseTransitionCommand.class);
        verify(leases).beginRun(start.capture());
        assertOwnership(start.getValue().scope());
        ArgumentCaptor<LeaseHeartbeatCommand> heartbeat =
                ArgumentCaptor.forClass(LeaseHeartbeatCommand.class);
        verify(leases).heartbeat(heartbeat.capture());
        assertOwnership(heartbeat.getValue().scope());
        verify(events, times(5)).append(any());
        verify(taskEvents, times(5)).append(any(), any());
        verify(outbox, times(5)).enqueue(any());
        verify(receipts, times(5)).complete(any(), any(), any(), any());

        ArgumentCaptor<DomainEventEnvelope<?>> envelope = envelopeCaptor();
        verify(events, times(5)).append(envelope.capture());
        WorkerTaskCommandAccepted progress = envelope.getAllValues().stream()
                .map(DomainEventEnvelope::payload)
                .map(WorkerTaskCommandAccepted.class::cast)
                .filter(value -> value.operation().equals("PROGRESS"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                workerActor.id(),
                envelope.getAllValues().get(0).actor().id().orElseThrow());
        DomainEventEnvelope<?> heartbeatEvent = envelope.getAllValues().stream()
                .filter(value -> ((WorkerTaskCommandAccepted) value.payload())
                        .operation().equals("HEARTBEAT"))
                .findFirst()
                .orElseThrow();
        assertEquals("EXECUTION_LEASE", heartbeatEvent.aggregate().type());
        assertEquals(authorization.scope().executionLeaseId().value(),
                heartbeatEvent.aggregate().id());
        assertEquals(Optional.of("完成安全检查"), progress.safeSummary());
        assertEquals(Optional.of(40), progress.progressPercent());
        assertFalse(progress.toString().contains("claim-token-plaintext"));
    }

    @Test
    void failPersistsOnlyStableFailureFacts() {
        TaskExecution failed = execution(8);
        ExecutionLease released = lease(6);
        when(leases.release(any())).thenReturn(new LeaseMutationResult(failed, released));

        service.fail(context("fail"), new WorkerFailCommand(
                7,
                5,
                new TaskExecutionFailure(
                        TaskExecutionFailureClass.MODEL_UNAVAILABLE,
                        "MODEL_PRIMARY_UNAVAILABLE")));

        ArgumentCaptor<DomainEventEnvelope<?>> envelope = envelopeCaptor();
        verify(events).append(envelope.capture());
        WorkerTaskCommandAccepted payload =
                (WorkerTaskCommandAccepted) envelope.getValue().payload();
        assertEquals(Optional.of("MODEL_UNAVAILABLE"), payload.failureClass());
        assertEquals(Optional.of("MODEL_PRIMARY_UNAVAILABLE"), payload.failureCode());
        assertTrue(payload.safeSummary().isEmpty());
    }

    @Test
    void exactReplayReturnsTheOriginalReceiptWithoutRepeatingMutationOrAudit() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 9, UUID.randomUUID());
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(receipt));

        var replay = service.heartbeat(
                context("same-key"), new WorkerHeartbeatCommand(8));

        assertTrue(replay.replayed());
        assertEquals(receipt, replay.receipt());
        verify(leases, never()).heartbeat(any());
        verify(events, never()).append(any());
        verify(outbox, never()).enqueue(any());
        verify(receipts, never()).complete(any(), any(), any(), any());
    }

    @Test
    void propagatesVersionAndDuplicateTerminalConflictsWithoutForgingReceipts() {
        when(leases.beginRun(any())).thenThrow(new OptimisticLockConflictException(
                "TaskExecution", authorization.scope().taskExecutionId(), 2, 3));

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.start(context("version-conflict"),
                        new WorkerTransitionCommand(2, 0)));

        when(leases.release(any())).thenThrow(new InvalidStateTransitionException(
                "TaskExecution",
                authorization.scope().taskExecutionId(),
                io.crewscope.domain.task.TaskExecutionStatus.COMPLETED,
                io.crewscope.domain.task.TaskExecutionStatus.COMPLETED));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> service.complete(context("duplicate-terminal"),
                        new WorkerTransitionCommand(5, 2)));

        verify(events, never()).append(any());
        verify(outbox, never()).enqueue(any());
        verify(receipts, never()).complete(any(), any(), any(), any());
    }

    private WorkerCommandContext context(String key) {
        return new WorkerCommandContext(
                authorization,
                IdempotencyKey.from("m3-a03/" + key),
                UUID.randomUUID(),
                Optional.empty());
    }

    private void assertOwnership(LeaseCommandScope actual) {
        TaskTokenGrantScope token = authorization.scope();
        assertEquals(token.workItemScope().organizationId(), actual.organizationId());
        assertEquals(token.executionLeaseId(), actual.leaseId());
        assertEquals(token.taskExecutionId(), actual.ownership().taskExecutionId());
        assertEquals(token.attempt(), actual.ownership().attempt());
        assertEquals(token.runtimeId(), actual.ownership().runtimeId());
        assertEquals(token.workerId(), actual.ownership().workerId());
        assertEquals(token.claimTokenHash(), actual.ownership().claimTokenHash());
        assertEquals(token.fencingToken(), actual.ownership().fencingToken());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<DomainEventEnvelope<?>> envelopeCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(DomainEventEnvelope.class);
    }

    private static TaskExecution execution(long version) {
        TaskExecution value = mock(TaskExecution.class);
        when(value.version()).thenReturn(version);
        return value;
    }

    private static ExecutionLease lease(long version) {
        ExecutionLease value = mock(ExecutionLease.class);
        when(value.version()).thenReturn(version);
        return value;
    }

    private static TaskTokenExecutionContext authorization() {
        OrganizationId organizationId = OrganizationId.generate();
        WorkItemScope workScope = new WorkItemScope(
                organizationId, TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
        ExecutionPrincipalSnapshot principal = new ExecutionPrincipalSnapshot(
                PrincipalId.generate(),
                ResponsibilityAssignmentId.generate(),
                2,
                TaskFactHash.sha256("responsibility"));
        TaskTokenGrantScope scope = new TaskTokenGrantScope(
                workScope,
                TaskId.generate(),
                TaskExecutionId.generate(),
                2,
                ExecutionLeaseId.generate(),
                new RuntimeEnvironment("test"),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                new ClaimTokenHash("a".repeat(64)),
                FencingToken.initial(),
                principal,
                PolicySnapshotId.generate(),
                TaskFactHash.sha256("policy"),
                new SafetyEnforcementOverlayReference(
                        SafetyEnforcementOverlayId.generate(),
                        1,
                        TaskFactHash.sha256("overlay")),
                Set.of("repository.read"),
                Set.of());
        return new TaskTokenExecutionContext(
                TaskCredentialGrantId.generate(),
                1,
                scope,
                UtcTimestamp.parse("2026-08-15T08:05:00.123456Z"));
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
