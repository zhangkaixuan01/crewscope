package io.crewscope.infrastructure.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Rebuilds the canonical external envelope from the normalized DomainEvent fact columns. */
public final class JdbcDomainEventJsonMapper {

    private final ObjectMapper objectMapper;

    public JdbcDomainEventJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String map(ResultSet resultSet) throws SQLException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", resultSet.getObject("event_id").toString());
        root.put("eventType", resultSet.getString("event_type"));
        root.put("schemaVersion", resultSet.getString("schema_version"));
        root.put("organizationId", resultSet.getObject("organization_id").toString());
        putNullable(root, "teamId", resultSet.getObject("team_id"));
        putNullable(root, "workspaceId", resultSet.getObject("workspace_id"));
        root.put("aggregateType", resultSet.getString("subject_type"));
        root.put("aggregateId", resultSet.getObject("subject_id").toString());
        root.put("aggregateVersion", resultSet.getLong("aggregate_version"));
        root.put("actorType", resultSet.getString("actor_type"));
        putNullable(root, "actorId", resultSet.getObject("actor_id"));
        root.put("correlationId", resultSet.getObject("correlation_id").toString());
        putNullable(root, "causationId", resultSet.getObject("causation_id"));
        putNullable(root, "idempotencyKey", resultSet.getString("idempotency_key"));
        OffsetDateTime occurredAt = resultSet
                .getObject("occurred_at", OffsetDateTime.class)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        root.put("occurredAt", occurredAt.toInstant().toString());
        JsonNode payload = objectMapper.readTree(resultSet.getString("payload"));
        if (payload == null || !payload.isObject()) {
            throw new IllegalStateException("Persisted DomainEvent payload must be a JSON object");
        }
        root.set("payload", payload);
        return objectMapper.writeValueAsString(root);
    }

    private void putNullable(ObjectNode root, String field, Object value) {
        if (value == null) {
            root.set(field, objectMapper.nullNode());
        } else {
            root.put(field, value.toString());
        }
    }
}
