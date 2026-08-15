package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.ClaimToken;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.LeaseOwnership;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.event.AgentRunEventRecorded;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DurableTaskExecutionEventServiceM3I07Test {

    private static final UtcTimestamp STARTED_AT =
            UtcTimestamp.parse("2026-08-15T05:00:00Z");
    private static final UtcTimestamp RECORDED_AT =
            UtcTimestamp.parse("2026-08-15T05:01:00Z");

    @Test
    void commitsAtomicSequencesAbsorbsExactReplayAndClosesOnConflictOrGap() {
        Fixture fixture = new Fixture();

        TaskRuntimeEventCommitResult first = fixture.commit(
                1, new TaskExecutionEventPayload.Progress("working", Optional.of(40)));
        TaskRuntimeEventCommitResult duplicate = fixture.commit(
                1, new TaskExecutionEventPayload.Progress("working", Optional.of(40)));

        assertEquals(TaskRuntimeEventCommitStatus.COMMITTED, first.status());
        assertEquals(TaskRuntimeEventCommitStatus.DUPLICATE, duplicate.status());
        assertEquals(first.domainEventId(), duplicate.domainEventId());
        assertEquals(1, fixture.events.size());
        assertEquals(1, fixture.outbox.size());
        assertThrows(DomainValidationException.class, () -> fixture.commit(
                1, new TaskExecutionEventPayload.Progress("changed", Optional.of(40))));
        assertThrows(DomainValidationException.class, () -> fixture.commit(
                3, new TaskExecutionEventPayload.Completed(Optional.empty())));

        TaskRuntimeEventCommitResult terminal = fixture.commit(
                2, new TaskExecutionEventPayload.Completed(Optional.empty()));
        assertEquals(AgentRunStatus.COMPLETED, terminal.agentRun().status());
        assertEquals(List.of(1L, 2L), fixture.receipts.values().stream()
                .map(TaskRuntimeEventReceipt::eventSequence).sorted().toList());
    }

    @Test
    void mapsApprovalToHashedPendingInterruptWithoutPublishingToken() {
        Fixture fixture = new Fixture();
        String secret = "server-only-resume-token";

        TaskRuntimeEventCommitResult result = fixture.commit(
                1,
                new TaskExecutionEventPayload.ApprovalRequired(
                        new ExecutionInterruptToken(secret),
                        ExecutionInterruptKind.TOOL_APPROVAL,
                        "Approve the controlled plan."));

        AgentInterrupt interrupt = fixture.interrupts.values().iterator().next();
        assertEquals(AgentRunStatus.INTERRUPTED, result.agentRun().status());
        assertEquals(RuntimeContentHash.sha256(secret), interrupt.interruptTokenHash());
        assertEquals(Optional.of(interrupt.id()), result.interruptId());
        assertFalse(fixture.events.get(0).payload().toString().contains(secret));
    }

    @Test
    void rollsBackTerminalStateWhenDomainEventAppendFails() {
        Fixture fixture = new Fixture();
        fixture.failEventAppend = true;

        assertThrows(IllegalStateException.class, () -> fixture.commit(
                1, new TaskExecutionEventPayload.Completed(Optional.empty())));

        assertEquals(AgentRunStatus.RUNNING, fixture.runRepository.current.status());
        assertTrue(fixture.receipts.isEmpty());
        assertTrue(fixture.events.isEmpty());
        assertTrue(fixture.outbox.isEmpty());
        assertTrue(fixture.interrupts.isEmpty());
    }

    @Test
    void rejectsNewEventsWhenTheLockedLeaseNoLongerOwnsTheExecution() {
        Fixture fixture = new Fixture();
        when(fixture.lease.owns(any(LeaseOwnership.class), eq(RECORDED_AT)))
                .thenReturn(false);

        assertThrows(DomainValidationException.class, () -> fixture.commit(
                1, new TaskExecutionEventPayload.Progress("stale owner", Optional.empty())));

        assertTrue(fixture.receipts.isEmpty());
        assertTrue(fixture.events.isEmpty());
        assertTrue(fixture.outbox.isEmpty());
    }

    @Test
    void resumesPendingInterruptExactlyOnceAndRejectsChangedReplay() {
        Fixture fixture = new Fixture();
        String token = "resume-token";
        TaskRuntimeEventCommitResult interrupted = fixture.commit(
                1,
                new TaskExecutionEventPayload.Paused(
                        new ExecutionInterruptToken(token), "Operator paused the Task."));
        UUID requestId = UUID.randomUUID();
        AgentRunResumeCommand command = new AgentRunResumeCommand(
                fixture.scope.organizationId(),
                interrupted.agentRun().id(),
                interrupted.interruptId().orElseThrow(),
                requestId,
                new ExecutionInterruptToken(token),
                RuntimeContentHash.sha256("approved response"),
                fixture.actor.id(),
                UUID.randomUUID(),
                Optional.empty());
        DurableAgentRunResumeService resumeService = new DurableAgentRunResumeService(
                fixture.runRepository,
                fixture.interruptRepository,
                fixture.principals,
                fixture.eventStore,
                fixture.outboxRepository,
                fixture.transactions,
                () -> RECORDED_AT);

        AgentRunResumeResult resumed = resumeService.resume(command);
        AgentRunResumeResult duplicate = resumeService.resume(command);

        assertEquals(AgentRunResumeStatus.RESUMED, resumed.status());
        assertEquals(AgentRunResumeStatus.DUPLICATE, duplicate.status());
        assertEquals(2, resumed.agentRun().currentSegment().sequence());
        assertThrows(DomainValidationException.class, () -> resumeService.resume(
                new AgentRunResumeCommand(
                        command.organizationId(),
                        command.agentRunId(),
                        command.interruptId(),
                        command.resumeRequestId(),
                        command.interruptToken(),
                        RuntimeContentHash.sha256("changed response"),
                        command.actorId(),
                        UUID.randomUUID(),
                        Optional.empty())));
    }

    private static final class Fixture {

        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(scope.organizationId(), scope.teamId()),
                PrincipalType.TEAM_AGENT,
                Optional.of(PrincipalId.generate()),
                "Task Agent",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                STARTED_AT);
        private final TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        private final Task task = mock(Task.class);
        private final TaskExecution execution = mock(TaskExecution.class);
        private final TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        private final ExecutionLease lease = mock(ExecutionLease.class);
        private final ExecutionLeaseRepository leaseRepository = mock(ExecutionLeaseRepository.class);
        private final InMemoryRunRepository runRepository;
        private final Map<String, TaskRuntimeEventReceipt> receipts = new HashMap<>();
        private final Map<AgentInterruptId, AgentInterrupt> interrupts = new HashMap<>();
        private final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
        private final List<PendingOutboxEvent> outbox = new ArrayList<>();
        private final PrincipalRepository principals;
        private final AgentInterruptRepository interruptRepository;
        private final DomainEventStore eventStore;
        private final OutboxRepository outboxRepository;
        private final TransactionExecutor transactions;
        private final DurableTaskExecutionEventService service;
        private boolean failEventAppend;

        private Fixture() {
            AgentRuntimeSessionId sessionId = AgentRuntimeSessionId.forTaskExecution(
                    executionId, Optional.empty(), profileId, "TASK");
            when(session.canInvoke()).thenReturn(true);
            when(session.id()).thenReturn(sessionId);
            when(session.scope()).thenReturn(scope);
            when(session.taskId()).thenReturn(taskId);
            when(session.executionId()).thenReturn(executionId);
            when(session.stepExecutionId()).thenReturn(Optional.empty());
            when(session.agentPrincipalId()).thenReturn(actor.id());
            when(session.agentProfileId()).thenReturn(profileId);
            when(session.agentProfileVersion()).thenReturn(2L);
            AgentRun run = AgentRun.start(
                    AgentRunId.generate(), session, 1, actor, STARTED_AT);
            runRepository = new InMemoryRunRepository(run);
            when(task.scope()).thenReturn(scope);
            when(task.id()).thenReturn(taskId);
            when(execution.id()).thenReturn(executionId);
            when(execution.attempt()).thenReturn(1);
            RuntimeEnvironment environment = new RuntimeEnvironment("test");
            ExecutionLeaseId leaseId = ExecutionLeaseId.generate();
            ExecutionRuntimeId runtimeId = ExecutionRuntimeId.generate();
            RuntimeWorkerId workerId = RuntimeWorkerId.generate();
            when(lease.id()).thenReturn(leaseId);
            when(lease.environment()).thenReturn(environment);
            when(lease.taskExecutionId()).thenReturn(executionId);
            when(lease.attempt()).thenReturn(1);
            when(lease.runtimeId()).thenReturn(runtimeId);
            when(lease.workerId()).thenReturn(workerId);
            when(lease.claimTokenHash()).thenReturn(
                    new ClaimToken("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq").hash());
            when(lease.fencingToken()).thenReturn(FencingToken.initial());
            when(lease.owns(any(LeaseOwnership.class), eq(RECORDED_AT))).thenReturn(true);
            when(leaseRepository.findByIdForUpdate(
                            scope.organizationId(), environment, leaseId))
                    .thenReturn(Optional.of(lease));
            when(facts.task()).thenReturn(task);
            when(facts.execution()).thenReturn(execution);
            when(facts.lease()).thenReturn(lease);
            when(facts.stepExecution()).thenReturn(Optional.empty());
            when(facts.runtimeSession()).thenReturn(session);
            when(facts.agentRun()).thenReturn(run);

            principals = mock(PrincipalRepository.class);
            when(principals.findById(scope.organizationId(), actor.id()))
                    .thenReturn(Optional.of(actor));
            RuntimeArtifactRepository artifacts = mock(RuntimeArtifactRepository.class);
            interruptRepository = interruptRepository();
            TaskRuntimeEventReceiptRepository receiptRepository = receiptRepository();
            eventStore = event -> {
                if (failEventAppend) {
                    throw new IllegalStateException("simulated append failure");
                }
                events.add(event);
            };
            outboxRepository = outbox::add;
            transactions = new TransactionExecutor() {
                @Override
                public <T> T required(Supplier<T> operation) {
                    AgentRun beforeRun = runRepository.current;
                    Map<String, TaskRuntimeEventReceipt> beforeReceipts =
                            new HashMap<>(receipts);
                    Map<AgentInterruptId, AgentInterrupt> beforeInterrupts =
                            new HashMap<>(interrupts);
                    int eventSize = events.size();
                    int outboxSize = outbox.size();
                    try {
                        return operation.get();
                    } catch (RuntimeException exception) {
                        runRepository.current = beforeRun;
                        receipts.clear();
                        receipts.putAll(beforeReceipts);
                        interrupts.clear();
                        interrupts.putAll(beforeInterrupts);
                        events.subList(eventSize, events.size()).clear();
                        outbox.subList(outboxSize, outbox.size()).clear();
                        throw exception;
                    }
                }
            };
            service = new DurableTaskExecutionEventService(
                    runRepository,
                    interruptRepository,
                    artifacts,
                    receiptRepository,
                    leaseRepository,
                    principals,
                    Fixture::encode,
                    eventStore,
                    outboxRepository,
                    transactions,
                    () -> RECORDED_AT);
        }

        private TaskRuntimeEventCommitResult commit(
                long sequence, TaskExecutionEventPayload payload) {
            AgentRun current = runRepository.current;
            TaskExecutionEvent event = new TaskExecutionEvent(
                    executionId,
                    1,
                    current.id(),
                    current.currentSegment().sequence(),
                    sequence,
                    RECORDED_AT,
                    payload);
            return service.commit(new TaskRuntimeEventCommitCommand(
                    facts, event, UUID.randomUUID(), Optional.empty()));
        }

        private TaskRuntimeEventReceiptRepository receiptRepository() {
            return new TaskRuntimeEventReceiptRepository() {
                @Override
                public TaskRuntimeEventCommitWindow lockCommitWindow(
                        OrganizationId organizationId,
                        AgentRunId agentRunId,
                        long segmentSequence,
                        long eventSequence) {
                    String key = key(agentRunId, segmentSequence, eventSequence);
                    long next = receipts.values().stream()
                            .filter(value -> value.agentRunId().equals(agentRunId)
                                    && value.segmentSequence() == segmentSequence)
                            .mapToLong(TaskRuntimeEventReceipt::eventSequence)
                            .max().orElse(0) + 1;
                    return new TaskRuntimeEventCommitWindow(
                            next, Optional.ofNullable(receipts.get(key)));
                }

                @Override
                public TaskRuntimeEventReceipt create(TaskRuntimeEventReceipt receipt) {
                    receipts.put(key(
                            receipt.agentRunId(),
                            receipt.segmentSequence(),
                            receipt.eventSequence()), receipt);
                    return receipt;
                }
            };
        }

        private AgentInterruptRepository interruptRepository() {
            return new AgentInterruptRepository() {
                @Override
                public AgentInterrupt createPending(AgentInterrupt interrupt) {
                    interrupts.put(interrupt.id(), interrupt);
                    return interrupt;
                }

                @Override
                public AgentInterrupt update(AgentInterrupt interrupt) {
                    interrupts.put(interrupt.id(), interrupt);
                    return interrupt;
                }

                @Override
                public Optional<AgentInterrupt> findById(
                        OrganizationId organizationId, AgentInterruptId interruptId) {
                    return Optional.ofNullable(interrupts.get(interruptId));
                }

                @Override
                public Optional<AgentInterrupt> findPendingByRun(
                        OrganizationId organizationId, AgentRunId agentRunId) {
                    return interrupts.values().stream()
                            .filter(value -> value.agentRunId().equals(agentRunId)
                                    && value.status()
                                            == io.crewscope.domain.task.AgentInterruptStatus.PENDING)
                            .findFirst();
                }

                @Override
                public Optional<AgentInterrupt> findByResumeRequestId(
                        OrganizationId organizationId, UUID resumeRequestId) {
                    return interrupts.values().stream()
                            .filter(value -> value.resolution()
                                    .filter(resolution -> resolution.resumeRequestId()
                                            .equals(resumeRequestId))
                                    .isPresent())
                            .findFirst();
                }
            };
        }

        private static TaskExecutionEventEncoding encode(TaskExecutionEvent event) {
            String secretAware = event.payload() instanceof TaskExecutionEventPayload.ApprovalRequired approval
                    ? approval.token().value() + approval.safePrompt()
                    : event.payload().toString();
            String kind = event.payload().getClass().getSimpleName()
                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
            AgentRunEventRecorded publicEvent = new AgentRunEventRecorded(
                    event.taskExecutionId().value(),
                    event.attempt(),
                    event.agentRunId().value(),
                    event.segmentSequence(),
                    event.sequence(),
                    kind,
                    event.occurredAt(),
                    event.payload() instanceof TaskExecutionEventPayload.ApprovalRequired approval
                            ? Optional.of(approval.safePrompt()) : Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            return new TaskExecutionEventEncoding(
                    RuntimeContentHash.sha256(secretAware), publicEvent);
        }

        private static String key(AgentRunId runId, long segment, long event) {
            return runId + ":" + segment + ":" + event;
        }
    }

    private static final class InMemoryRunRepository implements AgentRunRepository {
        private AgentRun current;

        private InMemoryRunRepository(AgentRun current) {
            this.current = current;
        }

        @Override
        public AgentRun createNext(AgentRun run) {
            current = run;
            return run;
        }

        @Override
        public AgentRun update(AgentRun run) {
            current = run;
            return run;
        }

        @Override
        public Optional<AgentRun> findById(
                OrganizationId organizationId, AgentRunId agentRunId) {
            return current.id().equals(agentRunId) && current.scope().organizationId().equals(organizationId)
                    ? Optional.of(current) : Optional.empty();
        }

        @Override
        public Optional<AgentRun> findActiveBySession(
                OrganizationId organizationId, AgentRuntimeSessionId sessionId) {
            return Optional.of(current);
        }

        @Override
        public List<AgentRun> findByExecution(
                OrganizationId organizationId, TaskExecutionId executionId) {
            return List.of(current);
        }

        @Override
        public List<AgentRun> findByStep(
                OrganizationId organizationId, StepExecutionId stepExecutionId) {
            return List.of();
        }
    }
}
