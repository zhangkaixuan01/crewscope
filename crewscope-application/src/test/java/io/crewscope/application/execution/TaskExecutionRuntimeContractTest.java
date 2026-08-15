package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
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
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** M3-I05 Task request, durable stream and explicit control Port contract. */
class TaskExecutionRuntimeContractTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.parse("2026-08-15T04:00:00Z");

    @Test
    void closesEveryRuntimeFactToTheCurrentServerOwnedExecution() {
        Fixture fixture = Fixture.create(AgentRunSegmentKind.INVOKE);
        TaskExecutionRuntimeFacts facts = fixture.facts();

        assertEquals(fixture.executionId, facts.execution().id());
        assertEquals(fixture.runId, facts.agentRun().id());
        assertFalse(facts.toString().contains(fixture.claimTokenHash.value()));

        TaskTokenGrantScope foreignScope = fixture.tokenScope(new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate()));
        TaskTokenExecutionContext foreignAuthorization = new TaskTokenExecutionContext(
                TaskCredentialGrantId.generate(), 0, foreignScope, fixture.tokenExpiry);
        assertThrows(IllegalArgumentException.class, () -> fixture.facts(foreignAuthorization));

        when(fixture.session.agentProfileVersion()).thenReturn(99L);
        assertThrows(IllegalArgumentException.class, fixture::facts);

        when(fixture.session.agentProfileVersion()).thenReturn(3L);
        when(fixture.session.canInvoke()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, fixture::request);
        assertEquals(
                TaskExecutionControlAction.CANCEL,
                fixture.control(TaskExecutionControlAction.CANCEL).action());
        assertEquals(
                TaskExecutionControlAction.PAUSE,
                fixture.control(TaskExecutionControlAction.PAUSE).action());

        Fixture disabledResume = Fixture.create(AgentRunSegmentKind.RESUME);
        when(disabledResume.session.canInvoke()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, disabledResume::request);
        assertThrows(IllegalArgumentException.class, () ->
                disabledResume.control(TaskExecutionControlAction.RESUME));
        assertEquals(
                TaskExecutionControlAction.CANCEL,
                disabledResume.control(TaskExecutionControlAction.CANCEL).action());
    }

    @Test
    void propagatesFiniteDemandAndCompletesAfterOneTerminal() {
        Fixture fixture = Fixture.create(AgentRunSegmentKind.INVOKE);
        TaskExecutionRequest request = fixture.request();
        DemandPublisher source = new DemandPublisher(List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.INVOKE)),
                fixture.event(2, new TaskExecutionEventPayload.Progress("working", Optional.of(50))),
                fixture.event(3, new TaskExecutionEventPayload.Completed(Optional.empty()))));
        TaskExecutionHandle handle = new TaskExecutionHandle(request, source);
        RecordingSubscriber subscriber = subscribe(handle);

        assertTrue(subscriber.events.isEmpty());
        subscriber.subscription.request(1);
        assertEquals(1, subscriber.events.size());
        assertFalse(subscriber.completed);

        subscriber.subscription.request(2);
        assertEquals(3, subscriber.events.size());
        assertTrue(subscriber.completed);
        assertNull(subscriber.failure);
        assertEquals(List.of(1L, 2L), source.requests);
        assertEquals(fixture.runId, handle.agentRunId());
    }

    @Test
    void allowsOneSubscriberAndKeepsTransportCancellationOutOfBusinessControl() {
        Fixture fixture = Fixture.create(AgentRunSegmentKind.INVOKE);
        DemandPublisher source = new DemandPublisher(List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.INVOKE)),
                fixture.event(2, new TaskExecutionEventPayload.Completed(Optional.empty()))));
        RecordingTaskRuntime runtime = new RecordingTaskRuntime(source);
        TaskExecutionHandle handle = runtime.executeTask(fixture.request());
        RecordingSubscriber first = subscribe(handle);
        RecordingSubscriber second = subscribe(handle);

        assertInstanceOf(ExecutionProtocolException.class, second.failure);
        first.subscription.request(1);
        first.subscription.cancel();
        first.subscription.request(1);

        assertTrue(source.canceled);
        assertEquals(1, first.events.size());
        assertFalse(first.completed);
        assertNull(first.failure);
        assertEquals(0, runtime.controlCalls.get());

        assertEquals(
                TaskExecutionControlResult.ACCEPTED,
                runtime.controlTask(fixture.control(TaskExecutionControlAction.CANCEL))
                        .toCompletableFuture().join());
        assertEquals(1, runtime.controlCalls.get());
    }

    @Test
    void rejectsWrongOwnerSequenceSegmentAndMissingTerminal() {
        Fixture fixture = Fixture.create(AgentRunSegmentKind.INVOKE);

        DemandPublisher invalidDemandSource = new DemandPublisher(List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.INVOKE)),
                fixture.event(2, new TaskExecutionEventPayload.Completed(Optional.empty()))));
        RecordingSubscriber invalidDemand = subscribe(
                new TaskExecutionHandle(fixture.request(), invalidDemandSource));
        invalidDemand.subscription.request(0);
        assertTrue(invalidDemandSource.canceled);
        assertInstanceOf(IllegalArgumentException.class, invalidDemand.failure);

        assertStreamFailure(fixture, List.of(
                fixture.event(1, new TaskExecutionEventPayload.Progress("no start", Optional.empty()))));
        assertStreamFailure(fixture, List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.RESUME))));
        assertStreamFailure(fixture, List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.INVOKE)),
                fixture.event(3, new TaskExecutionEventPayload.Completed(Optional.empty()))));
        assertStreamFailure(fixture, List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.INVOKE)),
                new TaskExecutionEvent(
                        TaskExecutionId.generate(), 1, fixture.runId, 1, 2, NOW,
                        new TaskExecutionEventPayload.Completed(Optional.empty()))));
        assertStreamFailure(fixture, List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.INVOKE)),
                fixture.event(2, new TaskExecutionEventPayload.Completed(Optional.empty())),
                fixture.event(3, new TaskExecutionEventPayload.Progress("late", Optional.empty()))));
        assertStreamFailure(fixture, List.of(
                fixture.event(1, new TaskExecutionEventPayload.Started(AgentRunSegmentKind.INVOKE)),
                fixture.event(2, new TaskExecutionEventPayload.Progress("unfinished", Optional.empty()))));
    }

    @Test
    void modelsPauseResumeAndCancelAsExplicitIdempotentBusinessControls() {
        Fixture invoke = Fixture.create(AgentRunSegmentKind.INVOKE);
        TaskExecutionControlRequest pause = invoke.control(TaskExecutionControlAction.PAUSE);
        TaskExecutionControlRequest cancel = invoke.control(TaskExecutionControlAction.CANCEL);

        assertEquals(TaskExecutionControlAction.PAUSE, pause.action());
        assertEquals(TaskExecutionControlAction.CANCEL, cancel.action());
        assertEquals(
                List.of(
                        TaskExecutionControlResult.ACCEPTED,
                        TaskExecutionControlResult.ALREADY_APPLIED,
                        TaskExecutionControlResult.ALREADY_TERMINAL,
                        TaskExecutionControlResult.STALE_OWNER,
                        TaskExecutionControlResult.NOT_FOUND),
                List.of(TaskExecutionControlResult.values()));
        assertThrows(IllegalArgumentException.class, () ->
                invoke.control(TaskExecutionControlAction.RESUME));

        Fixture resumeFixture = Fixture.create(AgentRunSegmentKind.RESUME);
        assertEquals(
                TaskExecutionControlAction.RESUME,
                resumeFixture.control(TaskExecutionControlAction.RESUME).action());
    }

    @Test
    void exposesSanitizedEventsAndStableFailureClassification() {
        ExecutionFailure failure = new ExecutionFailure(
                ExecutionFailureCategory.MODEL_RATE_LIMITED,
                true,
                "Model capacity is temporarily unavailable",
                Optional.of("MODEL_RATE_LIMITED"));
        ExecutionInterruptToken secret = new ExecutionInterruptToken("secret-pending-tool-token");

        assertEquals(
                TaskExecutionTerminalStatus.INTERRUPTED,
                new TaskExecutionEventPayload.ApprovalRequired(
                                secret, ExecutionInterruptKind.TOOL_APPROVAL, "Approve repository write")
                        .terminalStatus().orElseThrow());
        assertEquals(
                TaskExecutionTerminalStatus.PAUSED,
                new TaskExecutionEventPayload.Paused(
                        new ExecutionInterruptToken("pause-token"), "Paused at a safe point")
                        .terminalStatus().orElseThrow());
        assertEquals(
                TaskExecutionTerminalStatus.FAILED,
                new TaskExecutionEventPayload.Failed(failure)
                        .terminalStatus().orElseThrow());
        assertTrue(failure.retryable());
        assertFalse(secret.toString().contains(secret.value()));
        assertEquals(
                "first line\nsecond line",
                new TaskExecutionEventPayload.TextDelta("first line\nsecond line").text());
        assertThrows(IllegalArgumentException.class, () ->
                new TaskExecutionEventPayload.UsageReported(10, 2, 11, 12));
        assertThrows(IllegalArgumentException.class, () ->
                new TaskExecutionEventPayload.ThinkingSummary("unsafe\nprivate reasoning"));
    }

    private static RecordingSubscriber subscribe(TaskExecutionHandle handle) {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        handle.events().subscribe(subscriber);
        return subscriber;
    }

    private static void assertStreamFailure(Fixture fixture, List<TaskExecutionEvent> events) {
        RecordingSubscriber subscriber = subscribe(
                new TaskExecutionHandle(fixture.request(), new DemandPublisher(events)));
        subscriber.subscription.request(Long.MAX_VALUE);
        assertInstanceOf(ExecutionProtocolException.class, subscriber.failure);
        assertFalse(subscriber.completed);
    }

    private static final class DemandPublisher implements Flow.Publisher<TaskExecutionEvent> {

        private final List<TaskExecutionEvent> events;
        private final List<Long> requests = new ArrayList<>();
        private boolean canceled;

        private DemandPublisher(List<TaskExecutionEvent> events) {
            this.events = events;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super TaskExecutionEvent> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private int cursor;

                @Override
                public void request(long itemCount) {
                    requests.add(itemCount);
                    long remaining = itemCount;
                    while (!canceled && remaining > 0 && cursor < events.size()) {
                        subscriber.onNext(events.get(cursor++));
                        remaining--;
                    }
                    if (!canceled && cursor == events.size()) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    canceled = true;
                }
            });
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<TaskExecutionEvent> {

        private final List<TaskExecutionEvent> events = new ArrayList<>();
        private Flow.Subscription subscription;
        private Throwable failure;
        private boolean completed;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(TaskExecutionEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            failure = throwable;
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

    private static final class RecordingTaskRuntime implements TaskExecutionRuntime {

        private final Flow.Publisher<TaskExecutionEvent> source;
        private final AtomicInteger controlCalls = new AtomicInteger();

        private RecordingTaskRuntime(Flow.Publisher<TaskExecutionEvent> source) {
            this.source = source;
        }

        @Override
        public RuntimeDescriptor descriptor() {
            return new RuntimeDescriptor("contract-task-runtime", "Contract Task Runtime", "1.0.0");
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return RuntimeCapabilities.of(
                    RuntimeCapability.TASK_EXECUTION,
                    RuntimeCapability.STREAMING,
                    RuntimeCapability.DURABLE_EVENT_STREAM,
                    RuntimeCapability.PAUSE_RESUME,
                    RuntimeCapability.CANCEL,
                    RuntimeCapability.SESSION_STATE);
        }

        @Override
        public TaskExecutionHandle executeTask(TaskExecutionRequest request) {
            return new TaskExecutionHandle(request, source);
        }

        @Override
        public CompletionStage<TaskExecutionControlResult> controlTask(
                TaskExecutionControlRequest request) {
            controlCalls.incrementAndGet();
            return CompletableFuture.completedFuture(TaskExecutionControlResult.ACCEPTED);
        }
    }

    private static final class Fixture {

        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final AgentProfileId agentProfileId = AgentProfileId.generate();
        private final AgentRuntimeSessionId sessionId =
                AgentRuntimeSessionId.forTaskExecution(
                        executionId, Optional.empty(), agentProfileId, "TASK");
        private final AgentRunId runId = AgentRunId.generate();
        private final ExecutionLeaseId leaseId = ExecutionLeaseId.generate();
        private final RuntimeEnvironment environment = new RuntimeEnvironment("test");
        private final ExecutionRuntimeId runtimeId = ExecutionRuntimeId.generate();
        private final RuntimeWorkerId workerId = RuntimeWorkerId.generate();
        private final ClaimTokenHash claimTokenHash = new ClaimTokenHash("a".repeat(64));
        private final FencingToken fencingToken = FencingToken.initial();
        private final PrincipalId agentPrincipalId = PrincipalId.generate();
        private final PolicySnapshotId policyId = PolicySnapshotId.generate();
        private final TaskFactHash policyHash = TaskFactHash.sha256("policy");
        private final SafetyEnforcementOverlayReference overlayReference =
                new SafetyEnforcementOverlayReference(
                        SafetyEnforcementOverlayId.generate(), 1, TaskFactHash.sha256("overlay"));
        private final UtcTimestamp tokenExpiry =
                UtcTimestamp.parse("2026-08-15T04:05:00Z");
        private final ExecutionPrincipalSnapshot executionPrincipal =
                new ExecutionPrincipalSnapshot(
                        agentPrincipalId,
                        ResponsibilityAssignmentId.generate(),
                        1,
                        TaskFactHash.sha256("responsibility"));
        private final Task task = mock(Task.class);
        private final TaskExecution execution = mock(TaskExecution.class);
        private final ExecutionLease lease = mock(ExecutionLease.class);
        private final TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        private final AgentRun run = mock(AgentRun.class);
        private final PolicySnapshot policy = mock(PolicySnapshot.class);
        private final SafetyEnforcementOverlay overlay = mock(SafetyEnforcementOverlay.class);
        private final AgentRunSegment segment;
        private final TaskTokenExecutionContext authorization;

        private Fixture(AgentRunSegmentKind segmentKind) {
            segment = new AgentRunSegment(
                    1,
                    segmentKind,
                    segmentKind == AgentRunSegmentKind.RESUME
                            ? Optional.of(io.crewscope.domain.task.AgentInterruptId.generate())
                            : Optional.empty(),
                    AgentRunSegmentStatus.ACTIVE,
                    NOW,
                    Optional.empty());
            TaskExecutionPlanningContext planning = new TaskExecutionPlanningContext(
                    executionPrincipal,
                    policyId,
                    policyHash,
                    overlayReference,
                    Optional.empty(),
                    Optional.empty());
            when(task.isClosed()).thenReturn(false);
            when(task.scope()).thenReturn(scope);
            when(task.id()).thenReturn(taskId);
            when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
            when(execution.scope()).thenReturn(scope);
            when(execution.taskId()).thenReturn(taskId);
            when(execution.id()).thenReturn(executionId);
            when(execution.attempt()).thenReturn(1);
            when(execution.status()).thenReturn(TaskExecutionStatus.RUNNING);
            when(execution.planningContext()).thenReturn(Optional.of(planning));
            when(execution.lastFencingToken()).thenReturn(Optional.of(fencingToken));
            when(lease.organizationId()).thenReturn(scope.organizationId());
            when(lease.taskExecutionId()).thenReturn(executionId);
            when(lease.attempt()).thenReturn(1);
            when(lease.id()).thenReturn(leaseId);
            when(lease.environment()).thenReturn(environment);
            when(lease.runtimeId()).thenReturn(runtimeId);
            when(lease.workerId()).thenReturn(workerId);
            when(lease.claimTokenHash()).thenReturn(claimTokenHash);
            when(lease.fencingToken()).thenReturn(fencingToken);
            when(lease.release()).thenReturn(Optional.empty());
            when(lease.expiresAt()).thenReturn(tokenExpiry);
            when(session.canInvoke()).thenReturn(true);
            when(session.id()).thenReturn(sessionId);
            when(session.scope()).thenReturn(scope);
            when(session.taskId()).thenReturn(taskId);
            when(session.executionId()).thenReturn(executionId);
            when(session.stepExecutionId()).thenReturn(Optional.empty());
            when(session.agentPrincipalId()).thenReturn(agentPrincipalId);
            when(session.agentProfileId()).thenReturn(agentProfileId);
            when(session.agentProfileVersion()).thenReturn(3L);
            when(run.id()).thenReturn(runId);
            when(run.status()).thenReturn(AgentRunStatus.RUNNING);
            when(run.currentSegment()).thenReturn(segment);
            when(run.scope()).thenReturn(scope);
            when(run.taskId()).thenReturn(taskId);
            when(run.executionId()).thenReturn(executionId);
            when(run.stepExecutionId()).thenReturn(Optional.empty());
            when(run.runtimeSessionId()).thenReturn(sessionId);
            when(run.agentPrincipalId()).thenReturn(agentPrincipalId);
            when(run.agentProfileId()).thenReturn(agentProfileId);
            when(run.agentProfileVersion()).thenReturn(3L);
            when(policy.scope()).thenReturn(scope);
            when(policy.taskId()).thenReturn(taskId);
            when(policy.executionId()).thenReturn(executionId);
            when(policy.executionPrincipal()).thenReturn(executionPrincipal);
            when(policy.id()).thenReturn(policyId);
            when(policy.snapshotHash()).thenReturn(policyHash);
            when(policy.agentProfileId()).thenReturn(agentProfileId);
            when(policy.agentProfileVersion()).thenReturn(3L);
            when(overlay.scope()).thenReturn(scope);
            when(overlay.taskId()).thenReturn(taskId);
            when(overlay.executionId()).thenReturn(executionId);
            when(overlay.reference()).thenReturn(overlayReference);
            authorization = new TaskTokenExecutionContext(
                    TaskCredentialGrantId.generate(), 0, tokenScope(scope), tokenExpiry);
        }

        private static Fixture create(AgentRunSegmentKind kind) {
            return new Fixture(kind);
        }

        private TaskExecutionRuntimeFacts facts() {
            return facts(authorization);
        }

        private TaskExecutionRuntimeFacts facts(TaskTokenExecutionContext tokenContext) {
            return new TaskExecutionRuntimeFacts(
                    task,
                    execution,
                    Optional.empty(),
                    lease,
                    session,
                    run,
                    policy,
                    overlay,
                    Optional.empty(),
                    tokenContext);
        }

        private TaskExecutionRequest request() {
            return new TaskExecutionRequest(facts(), UUID.randomUUID());
        }

        private TaskExecutionControlRequest control(TaskExecutionControlAction action) {
            return new TaskExecutionControlRequest(
                    facts(), action, UUID.randomUUID(), "operator requested " + action,
                    UUID.randomUUID());
        }

        private TaskExecutionEvent event(long sequence, TaskExecutionEventPayload payload) {
            return new TaskExecutionEvent(
                    executionId, 1, runId, 1, sequence, NOW, payload);
        }

        private TaskTokenGrantScope tokenScope(WorkItemScope tokenWorkScope) {
            return new TaskTokenGrantScope(
                    tokenWorkScope,
                    taskId,
                    executionId,
                    1,
                    leaseId,
                    environment,
                    runtimeId,
                    workerId,
                    claimTokenHash,
                    fencingToken,
                    executionPrincipal,
                    policyId,
                    policyHash,
                    overlayReference,
                    Set.of("repository.read"),
                    Set.of());
        }
    }
}
