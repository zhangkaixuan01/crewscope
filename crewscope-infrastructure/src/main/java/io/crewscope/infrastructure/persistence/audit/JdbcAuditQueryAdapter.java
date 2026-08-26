package io.crewscope.infrastructure.persistence.audit;

import io.crewscope.application.audit.AuditCursor;
import io.crewscope.application.audit.AuditEventTypeDefinition;
import io.crewscope.application.audit.AuditEventTypeRegistry;
import io.crewscope.application.audit.AuditExportBatch;
import io.crewscope.application.audit.AuditExportRequest;
import io.crewscope.application.audit.AuditPage;
import io.crewscope.application.audit.AuditQuery;
import io.crewscope.application.audit.AuditQueryFilter;
import io.crewscope.application.audit.AuditQueryPort;
import io.crewscope.domain.audit.AuditCorrelationReference;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.audit.AuditIdentityChain;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditProviderReference;
import io.crewscope.domain.audit.AuditQueryEvent;
import io.crewscope.domain.audit.AuditRedactedSummary;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.audit.AuditSummarySchema;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL newest-first Audit reader that never maps original DomainEvent payloads. */
@Repository
public class JdbcAuditQueryAdapter implements AuditQueryPort {

    private static final String SELECT = """
            SELECT event_id, organization_id, team_id, initiator_id, actor_type, actor_id,
                   agent_principal_id, event_type, event_category, subject_type, subject_id,
                   outcome, authorization_context::TEXT AS authorization_context,
                   domain_event_id, provider_binding_id, connection_id,
                   external_operation_hash, correlation_id, causation_id, schema_version,
                   retention_level, occurred_at, payload::TEXT AS safe_summary
            FROM crewscope.audit_event
            WHERE organization_id = ? AND team_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditEventTypeRegistry registry;

    public JdbcAuditQueryAdapter(
            JdbcTemplate jdbc, ObjectMapper objectMapper, AuditEventTypeRegistry registry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditPage find(AuditQuery query) {
        AuditQuery value = Objects.requireNonNull(query, "query");
        List<AuditQueryEvent> rows = query(
                value.cursorScope().organizationId().value(),
                value.cursorScope().teamId().value(),
                value.filter(),
                value.after(),
                value.limit() + 1);
        boolean hasMore = rows.size() > value.limit();
        return new AuditPage(
                value, hasMore ? rows.subList(0, value.limit()) : rows, hasMore);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditExportBatch export(AuditExportRequest request) {
        AuditExportRequest value = Objects.requireNonNull(request, "request");
        List<AuditQueryEvent> rows = query(
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.filter(),
                Optional.empty(),
                value.maximumRows());
        OffsetDateTime generatedAt = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
        return new AuditExportBatch(
                value, UtcTimestamp.from(Objects.requireNonNull(generatedAt, "generatedAt")), rows);
    }

    private List<AuditQueryEvent> query(
            UUID organizationId,
            UUID teamId,
            AuditQueryFilter filter,
            Optional<AuditCursor> after,
            int limit) {
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> parameters = new ArrayList<>(List.of(organizationId, teamId));
        appendFilter(sql, parameters, filter);
        after.ifPresent(cursor -> {
            sql.append(" AND (occurred_at, event_id) < (?, ?)");
            parameters.add(cursor.occurredAt().toOffsetDateTime());
            parameters.add(cursor.eventId().value());
        });
        sql.append(" ORDER BY occurred_at DESC, event_id DESC LIMIT ?");
        parameters.add(limit);
        return jdbc.query(sql.toString(), this::map, parameters.toArray());
    }

    private void appendFilter(
            StringBuilder sql, List<Object> parameters, AuditQueryFilter filter) {
        filter.occurredFrom().ifPresent(value -> {
            sql.append(" AND occurred_at >= ?");
            parameters.add(value.toOffsetDateTime());
        });
        filter.occurredBefore().ifPresent(value -> {
            sql.append(" AND occurred_at < ?");
            parameters.add(value.toOffsetDateTime());
        });
        appendIn(sql, parameters, "event_category", filter.categories().stream()
                .map(Enum::name).sorted().toList());
        appendIn(sql, parameters, "outcome", filter.outcomes().stream()
                .map(Enum::name).sorted().toList());
        appendIn(sql, parameters, "initiator_id", filter.initiatorIds().stream()
                .map(PrincipalId::value).sorted().toList());
        appendIn(sql, parameters, "actor_id", filter.actorIds().stream()
                .map(PrincipalId::value).sorted().toList());
        appendIn(sql, parameters, "agent_principal_id", filter.agentPrincipalIds().stream()
                .map(PrincipalId::value).sorted().toList());
        filter.subject().ifPresent(value -> {
            sql.append(" AND subject_type = ? AND subject_id = ?");
            parameters.add(value.type());
            parameters.add(value.id());
        });
        filter.providerBindingId().ifPresent(value -> {
            sql.append(" AND provider_binding_id = ?");
            parameters.add(value.value());
        });
        filter.correlationId().ifPresent(value -> {
            sql.append(" AND correlation_id = ?");
            parameters.add(value);
        });
    }

    private static void appendIn(
            StringBuilder sql, List<Object> parameters, String column, List<?> values) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');
        parameters.addAll(values);
    }

    private AuditQueryEvent map(ResultSet row, int ignored) throws SQLException {
        EventType eventType = EventType.from(row.getString("event_type"));
        SchemaVersion schemaVersion = SchemaVersion.from(row.getString("schema_version"));
        AuditEventCategory category = AuditEventCategory.valueOf(row.getString("event_category"));
        AuditOutcome outcome = AuditOutcome.valueOf(row.getString("outcome"));
        UUID actorId = row.getObject("actor_id", UUID.class);
        EventActor actor = new EventActor(
                EventActorType.valueOf(row.getString("actor_type")),
                Optional.ofNullable(actorId).map(PrincipalId::new));
        AuditRedactedSummary summary = summary(
                eventType,
                schemaVersion,
                category,
                outcome,
                row.getString("authorization_context"),
                row.getString("safe_summary"));
        return new AuditQueryEvent(
                new AuditEventId(row.getObject("event_id", UUID.class)),
                new io.crewscope.domain.shared.id.OrganizationId(
                        row.getObject("organization_id", UUID.class)),
                new io.crewscope.domain.shared.id.TeamId(row.getObject("team_id", UUID.class)),
                category,
                outcome,
                new AuditIdentityChain(
                        Optional.ofNullable(row.getObject("initiator_id", UUID.class))
                                .map(PrincipalId::new),
                        actor,
                        Optional.ofNullable(row.getObject("agent_principal_id", UUID.class))
                                .map(PrincipalId::new)),
                new AggregateReference(
                        row.getString("subject_type"),
                        row.getObject("subject_id", UUID.class)),
                providerReference(row),
                new AuditCorrelationReference(
                        row.getObject("correlation_id", UUID.class),
                        Optional.ofNullable(row.getObject("causation_id", UUID.class)),
                        Optional.ofNullable(row.getObject("domain_event_id", UUID.class))),
                AuditRetentionLevel.valueOf(row.getString("retention_level")),
                UtcTimestamp.from(row.getObject("occurred_at", OffsetDateTime.class)),
                summary);
    }

    private Optional<AuditProviderReference> providerReference(ResultSet row) throws SQLException {
        UUID bindingId = row.getObject("provider_binding_id", UUID.class);
        UUID connectionId = row.getObject("connection_id", UUID.class);
        if (bindingId == null && connectionId == null) {
            return Optional.empty();
        }
        if (bindingId == null || connectionId == null) {
            throw new IllegalStateException("Stored Audit Provider reference is incomplete");
        }
        return Optional.of(new AuditProviderReference(
                new ProviderBindingId(bindingId),
                new ConnectionId(connectionId),
                Optional.ofNullable(row.getString("external_operation_hash"))
                        .map(TaskFactHash::new)));
    }

    private AuditRedactedSummary summary(
            EventType eventType,
            SchemaVersion schemaVersion,
            AuditEventCategory category,
            AuditOutcome outcome,
            String authorizationJson,
            String summaryJson) {
        JsonNode authorization = object(authorizationJson, "authorization");
        JsonNode classificationNode = authorization.get("classification");
        String classification = classificationNode != null && classificationNode.isString()
                ? classificationNode.stringValue()
                : "LEGACY";
        if (!classification.equals("REVIEWED")) {
            return emptySummary(eventType, schemaVersion, category);
        }
        JsonNode result = authorization.get("result");
        if (result == null || !result.isString() || !result.stringValue().equals(outcome.name())) {
            throw new IllegalStateException("Stored Audit authorization result is inconsistent");
        }
        AuditEventTypeDefinition definition = registry.find(eventType, schemaVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "Stored REVIEWED Audit event is not registered"));
        if (definition.category() != category) {
            throw new IllegalStateException("Stored Audit category differs from its Registry");
        }
        return definition.projectSummary(stringMap(summaryJson, "safe summary"));
    }

    private static AuditRedactedSummary emptySummary(
            EventType eventType,
            SchemaVersion schemaVersion,
            AuditEventCategory category) {
        return new AuditSummarySchema(eventType, schemaVersion, category, java.util.Set.of(),
                        java.util.Set.of())
                .project(Map.of());
    }

    private Map<String, String> stringMap(String json, String name) {
        JsonNode root = object(json, name);
        Map<String, String> values = new LinkedHashMap<>();
        root.properties().forEach(entry -> {
            if (!entry.getValue().isString()) {
                throw new IllegalStateException("Stored Audit " + name + " values must be strings");
            }
            values.put(entry.getKey(), entry.getValue().stringValue());
        });
        return values;
    }

    private JsonNode object(String json, String name) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Stored Audit " + name + " must be an object");
            }
            return root;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored Audit " + name + " is invalid", exception);
        }
    }
}
