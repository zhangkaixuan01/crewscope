package io.crewscope.infrastructure.event;

import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** One leased Outbox row and the canonical DomainEvent loaded through its foreign key. */
public record ClaimedOutboxEvent(
        UUID outboxId,
        UUID claimToken,
        UUID eventId,
        String topic,
        String partitionKey,
        int retryCount,
        UtcTimestamp occurredAt,
        String eventJson) {

    public ClaimedOutboxEvent {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(partitionKey, "partitionKey");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(eventJson, "eventJson");
    }

    /** Creates transport data without exposing the internal Claim Token. */
    public EventPublication publication() {
        return new EventPublication(
                outboxId,
                eventId,
                topic,
                partitionKey,
                retryCount + 1,
                occurredAt,
                eventJson);
    }
}
