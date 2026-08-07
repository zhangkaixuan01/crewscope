package io.crewscope.application.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Initial immutable data required to create a PENDING Outbox row. */
public record PendingOutboxEvent(
        UUID id,
        UUID domainEventId,
        String topic,
        String partitionKey,
        UtcTimestamp createdAt) {

    public static final String DOMAIN_EVENTS_TOPIC = "crewscope.domain-events.v1";
    public static final int MAX_TOPIC_LENGTH = 200;
    public static final int MAX_PARTITION_KEY_LENGTH = 200;

    public PendingOutboxEvent {
        id = requireUuid(id, "id");
        domainEventId = requireUuid(domainEventId, "domainEventId");
        topic = requireText(topic, "topic", MAX_TOPIC_LENGTH);
        partitionKey = requireText(partitionKey, "partitionKey", MAX_PARTITION_KEY_LENGTH);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Creates the standard publication request for one persisted domain event. */
    public static PendingOutboxEvent fromDomain(
            UUID outboxId, DomainEventEnvelope<? extends DomainEvent> event) {
        DomainEventEnvelope<? extends DomainEvent> source =
                Objects.requireNonNull(event, "event");
        String partitionKey = "%s:%s:%s"
                .formatted(
                        source.organizationId(),
                        source.aggregate().type(),
                        source.aggregate().id());
        return new PendingOutboxEvent(
                outboxId,
                source.eventId(),
                DOMAIN_EVENTS_TOPIC,
                partitionKey,
                source.occurredAt());
    }

    private static UUID requireUuid(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(name + " must not use the nil UUID");
        }
        return required;
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + maximumLength + " characters");
        }
        return normalized;
    }
}
