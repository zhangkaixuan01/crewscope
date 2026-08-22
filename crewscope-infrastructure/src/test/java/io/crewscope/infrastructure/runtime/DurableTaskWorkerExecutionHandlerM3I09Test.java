package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.TaskAgentStateRuntime;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionHandle;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlRequest;
import io.crewscope.application.execution.TaskExecutionControlResult;
import io.crewscope.application.execution.TaskExecutionRuntime;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.LeaseCommandScope;
import io.crewscope.application.task.LeaseReleaseCommand;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskControlRequestIds;
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
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentInterruptId;
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
import io.crewscope.domain.task.TaskExecutionControlRequestType;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecution;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecutionLifecycle;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    void authorizesResumeBeforeStartingTheAgentScopeExecution() {
        Fixture fixture = fixture();
        AgentInterruptId interruptId = new AgentInterruptId(UUID.randomUUID());
        when(fixture.run.currentSegment()).thenReturn(new AgentRunSegment(
                2,
                AgentRunSegmentKind.RESUME,
                Optional.of(interruptId),
                AgentRunSegmentStatus.ACTIVE,
                UtcTimestamp.parse("2026-08-15T06:00:05Z"),
                Optional.empty()));
        when(fixture.runtime.controlTask(any())).thenReturn(
                CompletableFuture.completedFuture(TaskExecutionControlResult.ACCEPTED));
        TaskExecutionHandle resumedHandle = mock(TaskExecutionHandle.class);
        TaskExecutionEvent resumedTerminal = new TaskExecutionEvent(
                fixture.facts.execution().id(),
                fixture.facts.execution().attempt(),
                fixture.run.id(),
                2,
                1,
                UtcTimestamp.parse("2026-08-15T06:00:10Z"),
                new TaskExecutionEventPayload.Completed(Optional.empty()));
        when(resumedHandle.events()).thenReturn(singleEvent(resumedTerminal));
        when(fixture.runtime.executeTask(any())).thenReturn(resumedHandle);

        assertDoesNotThrow(() -> handler.execute(fixture.receipt));

        var ordered = inOrder(fixture.runtime);
        ArgumentCaptor<TaskExecutionControlRequest> control =
                ArgumentCaptor.forClass(TaskExecutionControlRequest.class);
        ordered.verify(fixture.runtime).controlTask(control.capture());
        ordered.verify(fixture.runtime).executeTask(any());
        assertEquals(TaskExecutionControlAction.RESUME, control.getValue().action());
        assertEquals(interruptId.value(), control.getValue().controlRequestId());
    }

    @Test
    void committedCancelRequestWinsARaceWithACompletedRuntimeTerminal() {
        Fixture fixture = fixture();
        when(fixture.committedExecution.status()).thenReturn(TaskExecutionStatus.CANCEL_REQUESTED);

        assertDoesNotThrow(() -> handler.execute(fixture.receipt));

        ArgumentCaptor<LeaseReleaseCommand> release =
                ArgumentCaptor.forClass(LeaseReleaseCommand.class);
        verify(fixture.leaseCoordinator).release(release.capture());
        assertEquals(ExecutionLeaseReleaseReason.CANCELLED, release.getValue().reason());
    }

    @Test
    void sealedCodingResultWinsAPauseThatArrivesAfterTheFinalSafePoint() {
        Fixture fixture = fixture();
        TaskExecutionId executionId = fixture.facts.execution().id();
        OrganizationId organizationId = fixture.facts.task().scope().organizationId();
        CodingWorkspaceExecution codingWorkspace = mock(CodingWorkspaceExecution.class);
        when(codingWorkspace.hasSealedSuccessfulResult()).thenReturn(true);
        CodingWorkspaceExecutionLifecycle lifecycle = mock(CodingWorkspaceExecutionLifecycle.class);
        when(fixture.factory.codingLifecycle()).thenReturn(lifecycle);
        when(fixture.factory.prepare(fixture.receipt)).thenReturn(new TaskWorkerPreparedExecution(
                fixture.facts,
                fixture.prepared.leaseScope(),
                fixture.prepared.token(),
                fixture.prepared.correlationId(),
                Optional.of(codingWorkspace)));
        TaskExecution pauseRequested = mock(TaskExecution.class);
        when(pauseRequested.id()).thenReturn(executionId);
        when(pauseRequested.version()).thenReturn(4L);
        when(pauseRequested.status()).thenReturn(TaskExecutionStatus.PAUSE_REQUESTED);
        TaskExecution completed = mock(TaskExecution.class);
        when(completed.status()).thenReturn(TaskExecutionStatus.COMPLETED);
        when(fixture.executionRepository.findById(organizationId, executionId))
                .thenReturn(
                        Optional.of(pauseRequested),
                        Optional.of(pauseRequested),
                        Optional.of(completed));

        assertDoesNotThrow(() -> handler.execute(fixture.receipt));

        ArgumentCaptor<LeaseReleaseCommand> release =
                ArgumentCaptor.forClass(LeaseReleaseCommand.class);
        verify(fixture.leaseCoordinator).release(release.capture());
        assertEquals(ExecutionLeaseReleaseReason.COMPLETED, release.getValue().reason());
    }

    @Test
    void retriesLeaseReleaseAfterAConcurrentTaskExecutionVersionChange() {
        Fixture fixture = fixture();
        TaskExecutionId executionId = fixture.facts.execution().id();
        OrganizationId organizationId = fixture.facts.task().scope().organizationId();
        TaskExecution refreshed = mock(TaskExecution.class);
        when(refreshed.id()).thenReturn(executionId);
        when(refreshed.version()).thenReturn(4L);
        when(refreshed.status()).thenReturn(TaskExecutionStatus.RUNNING);
        when(fixture.executionRepository.findById(organizationId, executionId))
                .thenReturn(
                        Optional.of(fixture.committedExecution),
                        Optional.of(refreshed));
        when(fixture.leaseCoordinator.release(any()))
                .thenThrow(new OptimisticLockConflictException(
                        "TaskExecution",
                        executionId,
                        3,
                        4))
                .thenReturn(mock(io.crewscope.application.task.LeaseMutationResult.class));

        assertDoesNotThrow(() -> handler.execute(fixture.receipt));

        ArgumentCaptor<LeaseReleaseCommand> releases =
                ArgumentCaptor.forClass(LeaseReleaseCommand.class);
        verify(fixture.leaseCoordinator, times(2)).release(releases.capture());
        assertEquals(3, releases.getAllValues().get(0).executionCommand().expectedExecutionVersion());
        assertEquals(4, releases.getAllValues().get(1).executionCommand().expectedExecutionVersion());
    }

    @Test
    void bracketsLeaseReleaseWithCodingWorkspaceFinalization() {
        Fixture fixture = fixture();
        CodingWorkspaceExecution codingWorkspace = mock(CodingWorkspaceExecution.class);
        CodingWorkspaceExecutionLifecycle lifecycle = mock(CodingWorkspaceExecutionLifecycle.class);
        when(fixture.factory.codingLifecycle()).thenReturn(lifecycle);
        TaskWorkerPreparedExecution codingPrepared = new TaskWorkerPreparedExecution(
                fixture.facts,
                fixture.prepared.leaseScope(),
                fixture.prepared.token(),
                fixture.prepared.correlationId(),
                Optional.of(codingWorkspace));
        when(fixture.factory.prepare(fixture.receipt)).thenReturn(codingPrepared);
        TaskExecution completed = mock(TaskExecution.class);
        when(completed.status()).thenReturn(TaskExecutionStatus.COMPLETED);
        when(fixture.executionRepository.findById(
                        fixture.facts.task().scope().organizationId(),
                        fixture.facts.execution().id()))
                .thenReturn(
                        Optional.of(fixture.committedExecution),
                        Optional.of(fixture.committedExecution),
                        Optional.of(completed));

        assertDoesNotThrow(() -> handler.execute(fixture.receipt));

        var ordered = inOrder(lifecycle, fixture.leaseCoordinator);
        ordered.verify(lifecycle).beforeRelease(
                codingWorkspace,
                fixture.committedExecution,
                fixture.lease,
                io.crewscope.application.execution.TaskExecutionTerminalStatus.COMPLETED);
        ordered.verify(fixture.leaseCoordinator).release(any());
        ordered.verify(lifecycle).afterRelease(
                codingWorkspace,
                completed,
                io.crewscope.application.execution.TaskExecutionTerminalStatus.COMPLETED);
    }

    @Test
    void keepsLeaseHeartbeatActiveWhileSpecialistExecutionIsRunning() throws Exception {
        Fixture fixture = fixture(Duration.ofMillis(10));
        CountDownLatch specialistEntered = new CountDownLatch(1);
        CountDownLatch specialistMayFinish = new CountDownLatch(1);
        when(fixture.specialistExecution.executeAfterTaskAgent(any(), any()))
                .thenAnswer(invocation -> {
                    specialistEntered.countDown();
                    assertTrue(specialistMayFinish.await(2, TimeUnit.SECONDS));
                    return invocation.getArgument(1);
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = executor.submit(() -> handler.execute(fixture.receipt));
            assertTrue(specialistEntered.await(2, TimeUnit.SECONDS));

            verify(fixture.leaseCoordinator, timeout(500).atLeastOnce()).heartbeat(any());
            specialistMayFinish.countDown();
            execution.get(2, TimeUnit.SECONDS);
        } finally {
            specialistMayFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void heartbeatPropagatesADurablePauseRequestToTheRuntime() throws Exception {
        Fixture fixture = fixture(Duration.ofMillis(10));
        io.crewscope.domain.task.TaskExecutionControlRequest durableRequest =
                new io.crewscope.domain.task.TaskExecutionControlRequest(
                        TaskExecutionControlRequestType.PAUSE,
                        PrincipalId.generate(),
                        UtcTimestamp.parse("2026-08-15T06:00:06Z"),
                        "Pause for member review");
        when(fixture.committedExecution.status()).thenReturn(TaskExecutionStatus.PAUSE_REQUESTED);
        when(fixture.committedExecution.controlRequest()).thenReturn(Optional.of(durableRequest));

        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch propagated = new CountDownLatch(1);
        AtomicReference<TaskExecutionControlRequest> propagatedRequest = new AtomicReference<>();
        TaskExecutionHandle stalledHandle = mock(TaskExecutionHandle.class);
        when(stalledHandle.events()).thenReturn(subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        subscribed.countDown();
                    }

                    @Override
                    public void cancel() {
                        // Owner stop terminates the subscriber without requiring a publisher signal.
                    }
                }));
        when(fixture.runtime.executeTask(any())).thenReturn(stalledHandle);
        when(fixture.runtime.controlTask(any())).thenAnswer(invocation -> {
            propagatedRequest.set(invocation.getArgument(0));
            propagated.countDown();
            return CompletableFuture.completedFuture(TaskExecutionControlResult.ACCEPTED);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = executor.submit(() -> handler.execute(fixture.receipt));
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            assertTrue(propagated.await(2, TimeUnit.SECONDS));

            TaskExecutionControlRequest control = propagatedRequest.get();
            assertEquals(TaskExecutionControlAction.PAUSE, control.action());
            assertEquals(
                    TaskControlRequestIds.from(
                            fixture.committedExecution.id(), durableRequest),
                    control.controlRequestId());
            assertEquals(durableRequest.reason(), control.reason());

            handler.requestStop(fixture.receipt.leaseId());
            assertThrows(ExecutionException.class, () -> execution.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
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
        return fixture(Duration.ofSeconds(5));
    }

    private Fixture fixture(Duration heartbeatInterval) {
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
        when(runtimeExecution.status()).thenReturn(TaskExecutionStatus.RUNNING);
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
        TaskWorkerSpecialistExecution specialistExecution = mock(
                TaskWorkerSpecialistExecution.class);
        when(specialistExecution.executeAfterTaskAgent(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
                specialistExecution,
                eventService,
                leaseCoordinator,
                executionRepository,
                leaseRepository,
                snapshotRepository,
                tokenService,
                timeProvider,
                registration,
                new TaskWorkerExecutionSpec(Duration.ofMinutes(5), heartbeatInterval, 8));
        return new Fixture(
                receipt,
                facts,
                prepared,
                factory,
                runtime,
                run,
                committedExecution,
                executionRepository,
                lease,
                stateRuntime,
                specialistExecution,
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
            TaskWorkerPreparedExecution prepared,
            DurableTaskWorkerExecutionFactory factory,
            TaskExecutionRuntime runtime,
            AgentRun run,
            TaskExecution committedExecution,
            TaskExecutionRepository executionRepository,
            ExecutionLease lease,
            TaskAgentStateRuntime stateRuntime,
            TaskWorkerSpecialistExecution specialistExecution,
            DurableTaskExecutionEventService eventService,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            TaskTokenService tokenService) {}
}
