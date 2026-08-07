package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Framework-neutral values required by ordered database projections. */
public record ProjectionEvent(
        UUID eventId,
        String eventType,
        String schemaVersion,
        UUID organizationId,
        Optional<UUID> teamId,
        Optional<UUID> workspaceId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        EventActorType actorType,
        Optional<UUID> actorId,
        UUID correlationId,
        Optional<UUID> causationId,
        UtcTimestamp occurredAt,
        String payloadJson) {

    public ProjectionEvent {
        Objects.requireNonNull(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        aggregateType = requireText(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        Objects.requireNonNull(actorType, "actorType");
        actorId = Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        payloadJson = requireText(payloadJson, "payloadJson");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
