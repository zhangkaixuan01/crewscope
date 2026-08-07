package io.crewscope.domain.shared.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable metadata and payload for a persisted business fact.
 *
 * <p>The envelope maps directly to the DomainEvent store. Optional scope and causation values are
 * explicit so older events remain readable as the contract gains optional fields.
 */
public record DomainEventEnvelope<T extends DomainEvent>(
        UUID eventId,
        EventType eventType,
        SchemaVersion schemaVersion,
        OrganizationId organizationId,
        Optional<TeamId> teamId,
        Optional<WorkspaceId> workspaceId,
        AggregateReference aggregate,
        long aggregateVersion,
        EventActor actor,
        UUID correlationId,
        Optional<UUID> causationId,
        Optional<String> idempotencyKey,
        UtcTimestamp occurredAt,
        T payload) {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    public DomainEventEnvelope {
        eventId = requireUuid(eventId, "eventId");
        eventType = Objects.requireNonNull(eventType, "eventType");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        aggregate = Objects.requireNonNull(aggregate, "aggregate");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        actor = Objects.requireNonNull(actor, "actor");
        correlationId = requireUuid(correlationId, "correlationId");
        causationId = requireOptionalUuid(causationId, "causationId");
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Objects.requireNonNull(payload, "payload");
    }

    private static Optional<String> normalizeIdempotencyKey(Optional<String> value) {
        Objects.requireNonNull(value, "idempotencyKey");
        return value.map(candidate -> {
            if (candidate.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey must not be blank");
            }
            String normalized = candidate.strip();
            if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "idempotencyKey must contain at most "
                                + MAX_IDEMPOTENCY_KEY_LENGTH
                                + " characters");
            }
            return normalized;
        });
    }

    static UUID requireUuid(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(name + " must not use the nil UUID");
        }
        return required;
    }

    static Optional<UUID> requireOptionalUuid(Optional<UUID> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(candidate -> requireUuid(candidate, name));
    }
}
