package io.crewscope.agentscope.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventExistenceCheck;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.execution.TaskRuntimeEventCommitResult;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.workitem.WorkItemScope;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M10-F03 chat usage facts: recordTelemetry commits exactly one MODEL_USAGE_FACT_RECORDED
 * per attributed logical call in the same REQUIRED unit of work as the UsageReported
 * receipt, with deterministic call/event ids keyed by execution attempt and event slot.
 * Unattributed (env-slot) calls keep the durable usage report but emit no fact.
 */
class DurableCodingSpecialistUsageFactTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final TaskExecutionId EXECUTION_ID = TaskExecutionId.generate();
    private static final ModelProviderKey PROVIDER =
            new ModelProviderKey("deepseek");
    private static final ModelId MODEL = new ModelId("deepseek-v4-flash");

    private DurableTaskExecutionEventService eventService;
    private DomainEventStore events;
    private DomainEventExistenceCheck existence;
    private OutboxRepository outbox;
    private AtomicBoolean transactionActive;
    private Set<UUID> knownEventIds;
    private DurableCodingSpecialistExecutionStore store;

    @BeforeEach
    void setUp() {
        eventService = mock(DurableTaskExecutionEventService.class);
        when(eventService.commit(any())).thenReturn(mock(TaskRuntimeEventCommitResult.class));
        events = mock(DomainEventStore.class);
        knownEventIds = new HashSet<>();
        doAnswer(invocation -> {
            knownEventIds.add(((DomainEventEnvelope<?>) invocation.getArgument(0)).eventId());
            return null;
        }).when(events).append(any(DomainEventEnvelope.class));
        existence = mock(DomainEventExistenceCheck.class);
        when(existence.exists(any(UUID.class)))
                .thenAnswer(invocation -> knownEventIds.contains(invocation.getArgument(0)));
        outbox = mock(OutboxRepository.class);
        transactionActive = new AtomicBoolean();
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                boolean nested = transactionActive.getAndSet(true);
                try {
                    return operation.get();
                } finally {
                    transactionActive.set(nested);
                }
            }
        };
        store = new DurableCodingSpecialistExecutionStore(
                eventService,
                mock(io.crewscope.application.execution.TaskAgentStateSnapshotService.class),
                mock(io.crewscope.application.task.AgentStateSnapshotRepository.class),
                mock(io.crewscope.application.coding.CodingCheckpointRepository.class),
                mock(io.crewscope.application.task.StepExecutionRepository.class),
                transactions,
                () -> {
                    assertTrue(transactionActive.get(),
                            "usage facts must be appended inside the REQUIRED transaction");
                    return io.crewscope.domain.shared.time.UtcTimestamp.parse(
                            "2026-10-05T12:00:00Z");
                },
                events,
                existence,
                outbox);
    }

    @Test
    void attributedCallsEmitOneFactInTheSameTransactionAsTheirUsageReport() {
        ModelConnectionId connectionId = ModelConnectionId.generate();
        ModelCallAttribution attribution = ModelCallAttribution.chat(
                PROVIDER, MODEL, connectionId, 3L, ResolvedModelRole.PRIMARY);
        CodingSpecialistTelemetry telemetry = new CodingSpecialistTelemetry(
                List.of(
                        new CodingSpecialistModelUsage(1200, 80, 900, 1280,
                                Optional.of(attribution)),
                        new CodingSpecialistModelUsage(20, 5, 0, 25)),
                List.of("read_file"));

        UUID correlationId = UUID.randomUUID();
        long next = store.recordTelemetry(facts(), 7, telemetry, correlationId);

        // Both model calls and the tool call keep their durable Agent events.
        assertEquals(10, next);
        // Only the attributed call becomes a usage fact.
        ArgumentCaptor<DomainEventEnvelope<?>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, times(1)).append(envelope.capture());
        verify(outbox, times(1)).enqueue(any(PendingOutboxEvent.class));

        DomainEventEnvelope<?> captured = envelope.getValue();
        assertEquals("MODEL_USAGE_FACT_RECORDED", captured.eventType().value());
        assertEquals(ORGANIZATION_ID, captured.organizationId());
        assertEquals(Optional.of(TEAM_ID), captured.teamId());
        assertEquals(correlationId, captured.correlationId());
        assertEquals("MODEL_USAGE_FACT", captured.aggregate().type());
        ModelUsageFactRecorded payload = (ModelUsageFactRecorded) captured.payload();
        assertEquals(ModelUsageRole.CHAT_PRIMARY, payload.role());
        assertEquals(1, payload.attempt());
        assertEquals(PROVIDER, payload.providerKey());
        assertEquals(MODEL, payload.modelId());
        assertEquals(connectionId, payload.connectionId());
        assertEquals(3L, payload.connectionVersion());
        assertEquals(1200, payload.usage().inputTokens());
        assertEquals(900, payload.usage().cachedTokens());
        assertEquals(1280, payload.usage().totalTokens());
    }

    @Test
    void replayingTheSameSequenceSkipsTheFactAppendViaTheExistenceProbe() {
        ModelCallAttribution attribution = ModelCallAttribution.chat(
                PROVIDER, MODEL, ModelConnectionId.generate(), 3L,
                ResolvedModelRole.PRIMARY);
        CodingSpecialistTelemetry telemetry = new CodingSpecialistTelemetry(
                List.of(new CodingSpecialistModelUsage(10, 4, 2, 14,
                        Optional.of(attribution))),
                List.of());

        TaskExecutionRuntimeFacts facts = facts();
        store.recordTelemetry(facts, 7, telemetry, UUID.randomUUID());
        // The receipt path converges DUPLICATE on replay; the deterministic event id then
        // hits the existence probe and the fact is never appended a second time.
        store.recordTelemetry(facts, 7, telemetry, UUID.randomUUID());

        UUID expectedCallId = UUID.nameUUIDFromBytes(
                ("io.crewscope/model-usage/execution/" + EXECUTION_ID.value() + "/1/7")
                        .getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<DomainEventEnvelope<?>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, times(1)).append(envelope.capture());
        verify(outbox, times(1)).enqueue(any(PendingOutboxEvent.class));
        assertEquals(expectedCallId,
                ((ModelUsageFactRecorded) envelope.getValue().payload())
                        .callId().value());
        assertEquals(
                UUID.nameUUIDFromBytes(
                        ("io.crewscope/model-usage/event/" + expectedCallId)
                                .getBytes(StandardCharsets.UTF_8)),
                envelope.getValue().eventId());
    }

    @Test
    void anAlreadyRecordedFactIsSkippedWithoutTouchingTheCanonicalLog() {
        ModelCallAttribution attribution = ModelCallAttribution.chat(
                PROVIDER, MODEL, ModelConnectionId.generate(), 3L,
                ResolvedModelRole.FALLBACK);
        CodingSpecialistTelemetry telemetry = new CodingSpecialistTelemetry(
                List.of(new CodingSpecialistModelUsage(10, 4, 2, 14,
                        Optional.of(attribution))),
                List.of());
        UUID expectedCallId = UUID.nameUUIDFromBytes(
                ("io.crewscope/model-usage/execution/" + EXECUTION_ID.value() + "/1/7")
                        .getBytes(StandardCharsets.UTF_8));
        knownEventIds.add(UUID.nameUUIDFromBytes(
                ("io.crewscope/model-usage/event/" + expectedCallId)
                        .getBytes(StandardCharsets.UTF_8)));

        long next = store.recordTelemetry(facts(), 7, telemetry, UUID.randomUUID());

        // The usage receipt is still committed; only the fact append is skipped.
        assertEquals(8, next);
        verify(eventService, times(1)).commit(any());
        verify(events, never()).append(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void unattributedCallsKeepTheUsageReportWithoutAnyFact() {
        CodingSpecialistTelemetry telemetry = new CodingSpecialistTelemetry(
                List.of(new CodingSpecialistModelUsage(20, 5, 0, 25)),
                List.of());

        long next = store.recordTelemetry(facts(), 3, telemetry, UUID.randomUUID());

        assertEquals(4, next);
        verify(eventService, times(1)).commit(any());
        verify(events, never()).append(any());
        verify(outbox, never()).enqueue(any());
    }

    /** Minimal facts() stub: recordTelemetry only reads execution, run and task scope. */
    private static TaskExecutionRuntimeFacts facts() {
        TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(EXECUTION_ID);
        when(execution.attempt()).thenReturn(1);
        when(facts.execution()).thenReturn(execution);
        AgentRun run = mock(AgentRun.class);
        when(run.id()).thenReturn(io.crewscope.domain.task.AgentRunId.generate());
        AgentRunSegment segment = mock(AgentRunSegment.class);
        when(segment.sequence()).thenReturn(1L);
        when(run.currentSegment()).thenReturn(segment);
        when(facts.agentRun()).thenReturn(run);
        Task task = mock(Task.class);
        WorkItemScope scope = mock(WorkItemScope.class);
        when(scope.organizationId()).thenReturn(ORGANIZATION_ID);
        when(scope.teamId()).thenReturn(TEAM_ID);
        when(task.scope()).thenReturn(scope);
        when(facts.task()).thenReturn(task);
        return facts;
    }
}
