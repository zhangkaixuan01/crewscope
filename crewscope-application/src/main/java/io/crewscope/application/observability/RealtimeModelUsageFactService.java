package io.crewscope.application.observability;

import io.crewscope.application.event.DomainEventExistenceCheck;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.execution.RealtimeUsageFactEmitter;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Realtime Task-runtime usage facts (M10-F03). One fact per model call, keyed by the
 * durable execution coordinates: execution, attempt, segment and event sequence. The
 * event-id existence probe makes replay idempotent without any new persisted state — a
 * replayed stream rebuilds the same ids and appends nothing the second time.
 */
public final class RealtimeModelUsageFactService implements RealtimeUsageFactEmitter {

    private static final String CALL_ID_NAMESPACE = "io.crewscope/model-usage/realtime/";
    private static final String USAGE_EVENT_ID_NAMESPACE =
            "io.crewscope/model-usage/event/";
    private static final String USAGE_EVENT_TYPE = "MODEL_USAGE_FACT_RECORDED";
    private static final String USAGE_AGGREGATE_TYPE = "MODEL_USAGE_FACT";

    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final DomainEventExistenceCheck existence;
    private final TransactionExecutor transactions;
    private final AuthoritativeTimeProvider timeProvider;

    public RealtimeModelUsageFactService(
            DomainEventStore events,
            OutboxRepository outbox,
            DomainEventExistenceCheck existence,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider) {
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.existence = Objects.requireNonNull(existence, "existence");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public boolean emit(
            TaskExecutionRuntimeFacts facts,
            ModelCallAttribution attribution,
            ModelTokenUsage usage,
            long segmentSequence,
            long sequence) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        ModelCallAttribution pinned = Objects.requireNonNull(attribution, "attribution");
        ModelTokenUsage tokens = Objects.requireNonNull(usage, "usage");
        ModelUsageFactId callId = new ModelUsageFactId(UUID.nameUUIDFromBytes(
                (CALL_ID_NAMESPACE
                        + required.execution().id().value() + "/"
                        + required.execution().attempt() + "/"
                        + segmentSequence + "/"
                        + sequence).getBytes(StandardCharsets.UTF_8)));
        UUID eventId = UUID.nameUUIDFromBytes(
                (USAGE_EVENT_ID_NAMESPACE + callId.value())
                        .getBytes(StandardCharsets.UTF_8));
        return Boolean.TRUE.equals(transactions.required(() -> {
            if (existence.exists(eventId)) {
                // The same durable coordinates were already projected: a replayed stream.
                return false;
            }
            UtcTimestamp occurredAt = timeProvider.now();
            ModelUsageFactRecorded payload = new ModelUsageFactRecorded(
                    callId,
                    pinned.role(),
                    1,
                    pinned.providerKey(),
                    pinned.modelId(),
                    pinned.connectionId(),
                    pinned.connectionVersion(),
                    tokens,
                    occurredAt);
            DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                    eventId,
                    EventType.from(USAGE_EVENT_TYPE),
                    SchemaVersion.V1,
                    required.task().scope().organizationId(),
                    Optional.of(required.task().scope().teamId()),
                    Optional.empty(),
                    AggregateReference.of(USAGE_AGGREGATE_TYPE, callId),
                    1L,
                    EventActor.anonymousService(),
                    callId.value(),
                    Optional.empty(),
                    Optional.empty(),
                    occurredAt,
                    payload);
            events.append(event);
            outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
            return true;
        }));
    }
}
