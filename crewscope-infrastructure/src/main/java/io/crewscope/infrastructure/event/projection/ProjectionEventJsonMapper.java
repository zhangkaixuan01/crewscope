package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Validates and maps a canonical persisted event JSON document for projection processing. */
public final class ProjectionEventJsonMapper {

    private final ObjectMapper objectMapper;

    public ProjectionEventJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Rejects transport metadata that does not match the canonical envelope. */
    public ProjectionEvent map(EventPublication publication) {
        EventPublication source = Objects.requireNonNull(publication, "publication");
        try {
            if (!PendingOutboxEvent.DOMAIN_EVENTS_TOPIC.equals(source.topic())) {
                throw invalid("transport topic is not the canonical DomainEvent topic");
            }
            JsonNode root = objectMapper.readTree(source.eventJson());
            if (root == null || !root.isObject()) {
                throw invalid("root must be a JSON object");
            }
            UUID eventId = uuid(root, "eventId");
            UUID organizationId = uuid(root, "organizationId");
            String aggregateType = text(root, "aggregateType");
            UUID aggregateId = uuid(root, "aggregateId");
            UtcTimestamp occurredAt = UtcTimestamp.parse(text(root, "occurredAt"));
            JsonNode payload = required(root, "payload");
            if (!payload.isObject()) {
                throw invalid("payload must be a JSON object");
            }
            String expectedPartition = "%s:%s:%s"
                    .formatted(organizationId, aggregateType, aggregateId);
            if (!eventId.equals(source.eventId())) {
                throw invalid("transport eventId does not match the envelope");
            }
            if (!occurredAt.equals(source.occurredAt())) {
                throw invalid("transport occurredAt does not match the envelope");
            }
            if (!expectedPartition.equals(source.partitionKey())) {
                throw invalid("transport partitionKey does not match the aggregate");
            }
            EventActorType actorType = EventActorType.valueOf(text(root, "actorType"));
            Optional<UUID> actorId = optionalUuid(root, "actorId");
            if (actorType != EventActorType.SERVICE && actorId.isEmpty()) {
                throw invalid("only a SERVICE actor may omit actorId");
            }
            return new ProjectionEvent(
                    eventId,
                    text(root, "eventType"),
                    text(root, "schemaVersion"),
                    organizationId,
                    optionalUuid(root, "teamId"),
                    optionalUuid(root, "workspaceId"),
                    aggregateType,
                    aggregateId,
                    nonNegativeLong(root, "aggregateVersion"),
                    actorType,
                    actorId,
                    uuid(root, "correlationId"),
                    optionalUuid(root, "causationId"),
                    occurredAt,
                    objectMapper.writeValueAsString(payload));
        } catch (InvalidProjectionEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException(
                    "Published event violates the projection contract", exception);
        }
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw invalid(field + " is required");
        }
        return value;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isString() || value.stringValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.stringValue();
    }

    private static UUID uuid(JsonNode root, String field) {
        try {
            String source = text(root, field).strip();
            UUID value = UUID.fromString(source);
            if (!value.toString().equalsIgnoreCase(source) || AggregateId.NIL_UUID.equals(value)) {
                throw new IllegalArgumentException("not canonical");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException(field + " must be a UUID", exception);
        }
    }

    private static Optional<UUID> optionalUuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw invalid(field + " must be UUID text or null");
        }
        try {
            String source = value.stringValue().strip();
            UUID parsed = UUID.fromString(source);
            if (!parsed.toString().equalsIgnoreCase(source)
                    || AggregateId.NIL_UUID.equals(parsed)) {
                throw new IllegalArgumentException("not canonical");
            }
            return Optional.of(parsed);
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException(field + " must be a UUID", exception);
        }
    }

    private static long nonNegativeLong(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static InvalidProjectionEventException invalid(String message) {
        return new InvalidProjectionEventException(message);
    }
}
