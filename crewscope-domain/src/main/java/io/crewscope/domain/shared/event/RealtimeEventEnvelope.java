package io.crewscope.domain.shared.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified envelope delivered by AG-UI, Conversation and Team realtime streams.
 *
 * <p>Projected domain events retain their source {@code domainEventId} and aggregate version while
 * receiving a stream-specific {@code eventId}. Transient AG-UI progress events omit the domain and
 * aggregate references.
 */
public record RealtimeEventEnvelope<T>(
        UUID eventId,
        Optional<UUID> domainEventId,
        StreamType streamType,
        EventType eventType,
        SchemaVersion schemaVersion,
        Optional<AggregateReference> aggregate,
        Optional<Long> aggregateVersion,
        UUID correlationId,
        Optional<UUID> causationId,
        UtcTimestamp occurredAt,
        T payload) {

    public RealtimeEventEnvelope {
        eventId = DomainEventEnvelope.requireUuid(eventId, "eventId");
        domainEventId = DomainEventEnvelope.requireOptionalUuid(domainEventId, "domainEventId");
        streamType = Objects.requireNonNull(streamType, "streamType");
        eventType = Objects.requireNonNull(eventType, "eventType");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        aggregate = Objects.requireNonNull(aggregate, "aggregate");
        aggregateVersion = Objects.requireNonNull(aggregateVersion, "aggregateVersion");
        if (aggregate.isPresent() != aggregateVersion.isPresent()) {
            throw new IllegalArgumentException(
                    "aggregate and aggregateVersion must either both be present or both be empty");
        }
        aggregateVersion.ifPresent(version -> {
            if (version < 0) {
                throw new IllegalArgumentException("aggregateVersion must not be negative");
            }
        });
        correlationId = DomainEventEnvelope.requireUuid(correlationId, "correlationId");
        causationId = DomainEventEnvelope.requireOptionalUuid(causationId, "causationId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Objects.requireNonNull(payload, "payload");
    }

    /** Projects one persisted business fact into a stream-specific event. */
    public static <T extends DomainEvent> RealtimeEventEnvelope<T> fromDomain(
            UUID realtimeEventId, StreamType streamType, DomainEventEnvelope<T> domainEvent) {
        DomainEventEnvelope<T> source = Objects.requireNonNull(domainEvent, "domainEvent");
        return new RealtimeEventEnvelope<>(
                realtimeEventId,
                Optional.of(source.eventId()),
                streamType,
                source.eventType(),
                source.schemaVersion(),
                Optional.of(source.aggregate()),
                Optional.of(source.aggregateVersion()),
                source.correlationId(),
                source.causationId(),
                source.occurredAt(),
                source.payload());
    }

    /** Creates an AG-UI progress event that does not claim to be a persisted business fact. */
    public static <T> RealtimeEventEnvelope<T> transientAgUi(
            UUID eventId,
            EventType eventType,
            SchemaVersion schemaVersion,
            UUID correlationId,
            Optional<UUID> causationId,
            UtcTimestamp occurredAt,
            T payload) {
        return new RealtimeEventEnvelope<>(
                eventId,
                Optional.empty(),
                StreamType.AG_UI,
                eventType,
                schemaVersion,
                Optional.empty(),
                Optional.empty(),
                correlationId,
                causationId,
                occurredAt,
                payload);
    }
}
