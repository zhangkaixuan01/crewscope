package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Stable keyset position for bounded DomainEvent history scans. */
public record ProjectionHistoryCursor(
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        UtcTimestamp occurredAt,
        UUID eventId) {

    public ProjectionHistoryCursor {
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        aggregateType = aggregateType.strip();
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        eventId = Objects.requireNonNull(eventId, "eventId");
    }
}
