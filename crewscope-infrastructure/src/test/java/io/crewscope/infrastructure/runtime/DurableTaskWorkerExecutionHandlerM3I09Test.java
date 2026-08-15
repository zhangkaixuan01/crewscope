package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.TaskAgentStateRuntime;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionHandle;
import io.crewscope.application.execution.TaskExecutionRuntime;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.LeaseCommandScope;
import io.crewscope.application.task.LeaseReleaseCommand;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskTokenIssueResult;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.AgentRunSegmentStatus;
import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ClaimToken;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Durable handler proof for ordered receipt, Snapshot, release and cleanup boundaries. */
class DurableTaskWorkerExecutionHandlerM3I09Test {

    private DurableTaskWorkerExecutionHandler handler;

    @AfterEach
    void closeHandler() {
        if (handler != null) {
            handler.close();
        }
    }

    @Test
    void commitsTerminalBeforeCheckpointAndCompletedRelease() {
        Fixture fixture = fixture();

        assertDoesNotThrow(() -> handler.execute(fixture.receipt));

        var ordered = inOrder(
                fixture.factory,
                fixture.runtime,
                fixture.eventService,
                fixture.stateRuntime,
                fixture.leaseCoordinator,
                fixture.tokenService);
        ordered.verify(fixture.factory).prepare(fixture.receipt);
        ordered.verify(fixture.runtime).executeTask(any());
        ordered.verify(fixture.eventService).commit(any());
        ordered.verify(fixture.stateRuntime).checkpointState(
                fixture.facts, 1, 1,
                io.crewscope.application.execution.TaskAgentStateSafePoint.CALL_COMPLETED);
        ordered.verify(fixture.leaseCoordinator).release(any());
        ordered.verify(fixture.tokenService).revoke(any());

        ArgumentCaptor<LeaseReleaseCommand> release =
                ArgumentCaptor.forClass(LeaseReleaseCommand.class);
        verify(fixture.leaseCoordinator).release(release.capture());
        assertEquals(ExecutionLeaseReleaseReason.COMPLETED, release.getValue().reason());
    }

    @Test
    void checkpointFailureNeverForgesCompletionAndFallsBackToRecoveryRelease() {
        Fixture fixture = fixture();
        when(fixture.stateRuntime.checkpointState(any(), any(Long.class), any(Long.class), any()))
                .thenThrow(new IllegalStateException("snapshot unavailable"));

        assertThrows(IllegalStateException.class, () -> handler.execute(fixture.receipt));

        ArgumentCaptor<LeaseReleaseCommand> release =
                ArgumentCaptor.forClass(LeaseReleaseCommand.class);
        verify(fixture.leaseCoordinator).release(release.capture());
        assertEquals(ExecutionLeaseReleaseReason.WORKER_SHUTDOWN, release.getValue().reason());
        verify(fixture.tokenService).revoke(any());
    }

    @Test
    void stopRequestUnblocksAFlowPublisherThatDoesNotSignalAfterCancel() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicBoolean canceled = new AtomicBoolean();
        TaskExecutionHandle stalledHandle = mock(TaskExecutionHandle.class);
        when(stalledHandle.events()).thenReturn(subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        subscribed.countDown();
                    }

                    @Override
                    public void cancel() {
                        // Reactive Streams does not require onComplete/onError after cancellation.
                        canceled.set(true);
                    }
                }));
        when(fixture.runtime.executeTask(any())).thenReturn(stalledHandle);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = executor.submit(() -> handler.execute(fixture.receipt));
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            handler.requestStop(fixture.receipt.leaseId());

            assertThrows(ExecutionException.class, () -> execution.get(2, TimeUnit.SECONDS));
            assertTrue(canceled.get());
            ArgumentCaptor<LeaseReleaseCommand> release =
                    ArgumentCaptor.forClass(LeaseReleaseCommand.class);
            verify(fixture.leaseCoordinator).release(release.capture());
            assertEquals(ExecutionLeaseReleaseReason.WORKER_SHUTDOWN,
                    release.getValue().reason());
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture() {
        OrganizationId organizationId = OrganizationId.generate();
        WorkItemScope scope = new WorkItemScope(
                organizationId,
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        TaskExecutionId executionId = TaskExecutionId.generate();
        AgentRunId runId = AgentRunId.generate();
        ExecutionRuntimeId runtimeId = new ExecutionRuntimeId(UUID.randomUUID());
        RuntimeWorkerId workerId = new RuntimeWorkerId(UUID.randomUUID());
        ClaimReceipt receipt = new ClaimReceipt(
                ExecutionLeaseId.generate(),
                executionId,
                1,
                runtimeId,
                workerId,
                new ClaimToken("B".repeat(43)),
                FencingToken.initial(),
                1,
                0,
                UtcTimestamp.parse("2026-08-15T06:00:30Z"));
        LeaseCommandScope leaseScope = new LeaseCommandScope(
                organizationId,
                new RuntimeEnvironment("test"),
                receipt.leaseId(),
                receipt.ownership());

        TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        Task task = mock(Task.class);
        when(task.scope()).thenReturn(scope);
        TaskExecution runtimeExecution = mock(TaskExecution.class);
        when(runtimeExecution.id()).thenReturn(executionId);
        when(runtimeExecution.attempt()).thenReturn(1);
        TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        when(session.canInvoke()).thenReturn(true);
        AgentRun run = mock(AgentRun.class);
        when(run.id()).thenReturn(runId);
        when(run.currentSegment()).thenReturn(new AgentRunSegment(
                1,
                AgentRunSegmentKind.INVOKE,
                Optional.empty(),
                AgentRunSegmentStatus.ACTIVE,
                UtcTimestamp.parse("2026-08-15T06:00:01Z"),
                Optional.empty()));
        when(facts.task()).thenReturn(task);
        when(facts.execution()).thenReturn(runtimeExecution);
        when(facts.runtimeSession()).thenReturn(session);
        when(facts.agentRun()).thenReturn(run);

        TaskTokenIssueResult token = mock(TaskTokenIssueResult.class);
        when(token.token()).thenReturn("token-value");
        TaskCredentialGrant grant = mock(TaskCredentialGrant.class);
        when(grant.version()).thenReturn(0L);
        when(token.grant()).thenReturn(grant);
        TaskWorkerPreparedExecution prepared = new TaskWorkerPreparedExecution(
                facts, leaseScope, token, UUID.randomUUID());

        DurableTaskWorkerExecutionFactory factory = mock(DurableTaskWorkerExecutionFactory.class);
        when(factory.prepare(receipt)).thenReturn(prepared);
        TaskExecutionRuntime runtime = mock(TaskExecutionRuntime.class);
        TaskExecutionHandle handle = mock(TaskExecutionHandle.class);
        TaskExecutionEvent terminal = new TaskExecutionEvent(
                executionId,
                1,
                runId,
                1,
                1,
                UtcTimestamp.parse("2026-08-15T06:00:10Z"),
                new TaskExecutionEventPayload.Completed(Optional.empty()));
        when(handle.events()).thenReturn(singleEvent(terminal));
        when(runtime.executeTask(any())).thenReturn(handle);
        TaskAgentStateRuntime stateRuntime = mock(TaskAgentStateRuntime.class);
        DurableTaskExecutionEventService eventService = mock(DurableTaskExecutionEventService.class);
        TaskExecutionLeaseCoordinator leaseCoordinator = mock(TaskExecutionLeaseCoordinator.class);
        TaskExecutionRepository executionRepository = mock(TaskExecutionRepository.class);
        TaskExecution committedExecution = mock(TaskExecution.class);
        when(committedExecution.id()).thenReturn(executionId);
        when(committedExecution.version()).thenReturn(3L);
        when(committedExecution.status()).thenReturn(TaskExecutionStatus.RUNNING);
        when(executionRepository.findById(organizationId, executionId))
                .thenReturn(Optional.of(committedExecution));
        ExecutionLeaseRepository leaseRepository = mock(ExecutionLeaseRepository.class);
        ExecutionLease lease = mock(ExecutionLease.class);
        when(lease.version()).thenReturn(4L);
        when(lease.release()).thenReturn(Optional.empty());
        when(lease.owns(any(), any())).thenReturn(true);
        when(leaseRepository.findById(organizationId, leaseScope.environment(), receipt.leaseId()))
                .thenReturn(Optional.of(lease));
        AgentStateSnapshotRepository snapshotRepository = mock(AgentStateSnapshotRepository.class);
        when(snapshotRepository.findRecoveryCandidates(organizationId, runId, 1))
                .thenReturn(List.of());
        TaskTokenService tokenService = mock(TaskTokenService.class);
        AuthoritativeTimeProvider timeProvider = () ->
                UtcTimestamp.parse("2026-08-15T06:00:15Z");
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Task Worker",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                UtcTimestamp.parse("2026-08-15T06:00:00Z"));
        RuntimeCapabilities capabilities = new RuntimeCapabilities(
                Set.of(RuntimeCapability.PLAN, RuntimeCapability.SESSION_STATE));
        RuntimeWorkerRegistrationSpec registration = new RuntimeWorkerRegistrationSpec(
                organizationId,
                leaseScope.environment(),
                "agentscope-java",
                "AgentScope Java",
                "2.0.0",
                capabilities,
                "worker-a",
                RuntimeProfile.WORKER,
                capabilities,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                actor);
        handler = new DurableTaskWorkerExecutionHandler(
                factory,
                runtime,
                stateRuntime,
                eventService,
                leaseCoordinator,
                executionRepository,
                leaseRepository,
                snapshotRepository,
                tokenService,
                timeProvider,
                registration,
                new TaskWorkerExecutionSpec(Duration.ofMinutes(5), Duration.ofSeconds(5), 8));
        return new Fixture(
                receipt,
                facts,
                factory,
                runtime,
                stateRuntime,
                eventService,
                leaseCoordinator,
                tokenService);
    }

    private static Flow.Publisher<TaskExecutionEvent> singleEvent(TaskExecutionEvent event) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean emitted = new AtomicBoolean();

            @Override
            public void request(long n) {
                if (n > 0 && emitted.compareAndSet(false, true)) {
                    subscriber.onNext(event);
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                emitted.set(true);
            }
        });
    }

    private record Fixture(
            ClaimReceipt receipt,
            TaskExecutionRuntimeFacts facts,
            DurableTaskWorkerExecutionFactory factory,
            TaskExecutionRuntime runtime,
            TaskAgentStateRuntime stateRuntime,
            DurableTaskExecutionEventService eventService,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            TaskTokenService tokenService) {}
}
