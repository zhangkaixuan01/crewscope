package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Locked persistent position for one organization, projection and event partition. */
public record ProjectionCheckpoint(
        UUID organizationId,
        String projectionName,
        String partitionKey,
        Optional<UUID> lastEventId,
        Optional<String> lastEventCursor,
        Optional<UtcTimestamp> lastEventOccurredAt,
        long version) {

    public ProjectionCheckpoint {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(projectionName, "projectionName");
        Objects.requireNonNull(partitionKey, "partitionKey");
        lastEventId = Objects.requireNonNull(lastEventId, "lastEventId");
        lastEventCursor = Objects.requireNonNull(lastEventCursor, "lastEventCursor");
        lastEventOccurredAt = Objects.requireNonNull(lastEventOccurredAt, "lastEventOccurredAt");
        if (lastEventId.isPresent() != lastEventCursor.isPresent()
                || lastEventId.isPresent() != lastEventOccurredAt.isPresent()) {
            throw new IllegalArgumentException("checkpoint event position must be complete");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
