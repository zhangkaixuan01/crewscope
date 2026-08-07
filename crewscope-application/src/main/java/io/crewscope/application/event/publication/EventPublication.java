package io.crewscope.application.event.publication;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Immutable transport message for one canonical persisted DomainEvent. */
public record EventPublication(
        UUID outboxId,
        UUID eventId,
        String topic,
        String partitionKey,
        int deliveryAttempt,
        UtcTimestamp occurredAt,
        String eventJson) {

    public EventPublication {
        outboxId = requireUuid(outboxId, "outboxId");
        eventId = requireUuid(eventId, "eventId");
        topic = requireText(topic, "topic");
        partitionKey = requireText(partitionKey, "partitionKey");
        if (deliveryAttempt < 1) {
            throw new IllegalArgumentException("deliveryAttempt must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        eventJson = requireText(eventJson, "eventJson");
    }

    private static UUID requireUuid(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(name + " must not use the nil UUID");
        }
        return required;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
