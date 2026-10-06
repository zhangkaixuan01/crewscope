package io.crewscope.application.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventExistenceCheck;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.execution.RealtimeUsageFactEmitter;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M10-F03 realtime usage facts: one fact per model call keyed by the durable execution
 * coordinates (execution, attempt, segment, event slot). The event-id existence probe makes
 * replay idempotent with zero new persisted state, and the append shares the caller's REQUIRED
 * transaction so a rollup never half-observes a fact.
 */
class RealtimeModelUsageFactServiceTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final TaskExecutionId EXECUTION_ID = TaskExecutionId.generate();
    private static final ModelProviderKey PROVIDER = new ModelProviderKey("deepseek");
    private static final ModelId MODEL = new ModelId("deepseek-v4-flash");

    private DomainEventStore events;
    private OutboxRepository outbox;
    private DomainEventExistenceCheck existence;
    private AtomicBoolean transactionActive;
    private RealtimeModelUsageFactService service;

    @BeforeEach
    void setUp() {
        events = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        existence = mock(DomainEventExistenceCheck.class);
        when(existence.exists(any(UUID.class))).thenReturn(false);
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
        service = new RealtimeModelUsageFactService(
                events,
                outbox,
                existence,
                transactions,
                () -> {
                    assertTrue(transactionActive.get(),
                            "realtime facts must be appended inside the REQUIRED transaction");
                    return UtcTimestamp.parse("2026-10-05T12:00:00Z");
                });
    }

    @Test
    void emissionAppendsOneFactWithDeterministicIdsAndFullEnvelope() {
        ModelCallAttribution attribution = ModelCallAttribution.chat(
                PROVIDER, MODEL, ModelConnectionId.generate(), 2L,
                ResolvedModelRole.FALLBACK);
        // Realtime coordinates: execution attempt comes from facts, segment and slot from the
        // durable event stream. Replay rebuilds the same ids byte for byte.
        boolean emitted = service.emit(
                facts(3),
                attribution,
                new ModelTokenUsage(500, 120, 300, 620),
                4,
                9);

        assertTrue(emitted);
        ArgumentCaptor<DomainEventEnvelope<?>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(events, times(1)).append(envelope.capture());
        verify(outbox, times(1)).enqueue(any(PendingOutboxEvent.class));

        UUID expectedCallId = UUID.nameUUIDFromBytes(
                ("io.crewscope/model-usage/realtime/"
                        + EXECUTION_ID.value() + "/3/4/9")
                        .getBytes(StandardCharsets.UTF_8));
        DomainEventEnvelope<?> captured = envelope.getValue();
        assertEquals(expectedCallId,
                ((ModelUsageFactRecorded) captured.payload()).callId().value());
        assertEquals(
                UUID.nameUUIDFromBytes(
                        ("io.crewscope/model-usage/event/" + expectedCallId)
                                .getBytes(StandardCharsets.UTF_8)),
                captured.eventId());
        assertEquals("MODEL_USAGE_FACT_RECORDED", captured.eventType().value());
        assertEquals(ORGANIZATION_ID, captured.organizationId());
        assertEquals(Optional.of(TEAM_ID), captured.teamId());
        assertEquals("MODEL_USAGE_FACT", captured.aggregate().type());
        assertEquals(expectedCallId, captured.aggregate().id());
        // The deterministic call id doubles as the correlation id for replay tracing.
        assertEquals(expectedCallId, captured.correlationId());
        assertEquals(UtcTimestamp.parse("2026-10-05T12:00:00Z"), captured.occurredAt());
        ModelUsageFactRecorded payload = (ModelUsageFactRecorded) captured.payload();
        assertEquals(ModelUsageRole.CHAT_FALLBACK, payload.role());
        assertEquals(1, payload.attempt());
        assertEquals(PROVIDER, payload.providerKey());
        assertEquals(MODEL, payload.modelId());
        assertEquals(2L, payload.connectionVersion());
        assertEquals(500, payload.usage().inputTokens());
        assertEquals(300, payload.usage().cachedTokens());
        assertEquals(620, payload.usage().totalTokens());
    }

    @Test
    void existingEventIdIsAReplayAndAppendsNothing() {
        when(existence.exists(any(UUID.class))).thenReturn(true);

        boolean emitted = service.emit(
                facts(1),
                ModelCallAttribution.chat(
                        PROVIDER, MODEL, ModelConnectionId.generate(), 0L,
                        ResolvedModelRole.PRIMARY),
                new ModelTokenUsage(10, 4, 0, 14),
                1,
                2);

        assertFalse(emitted);
        verify(events, never()).append(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void disabledEmitterKeepsTheNoopContract() {
        assertFalse(RealtimeUsageFactEmitter.disabled().emit(
                facts(1),
                ModelCallAttribution.chat(
                        PROVIDER, MODEL, ModelConnectionId.generate(), 0L,
                        ResolvedModelRole.PRIMARY),
                new ModelTokenUsage(1, 1, 0, 2),
                1,
                1));
    }

    /** Minimal facts stub: the service only reads execution coordinates and task scope. */
    private static TaskExecutionRuntimeFacts facts(int attempt) {
        TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(EXECUTION_ID);
        when(execution.attempt()).thenReturn(attempt);
        when(facts.execution()).thenReturn(execution);
        Task task = mock(Task.class);
        WorkItemScope scope = mock(WorkItemScope.class);
        when(scope.organizationId()).thenReturn(ORGANIZATION_ID);
        when(scope.teamId()).thenReturn(TEAM_ID);
        when(task.scope()).thenReturn(scope);
        when(facts.task()).thenReturn(task);
        return facts;
    }
}
