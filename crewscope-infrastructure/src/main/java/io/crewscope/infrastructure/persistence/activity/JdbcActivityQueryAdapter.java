package io.crewscope.infrastructure.persistence.activity;

import io.crewscope.application.activity.ActivityEventTypeDefinition;
import io.crewscope.application.activity.ActivityEventTypeRegistry;
import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.ActivityPage;
import io.crewscope.application.activity.ActivityQuery;
import io.crewscope.application.activity.ActivityQueryPort;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.activity.TeamActivityCursorExpiredException;
import io.crewscope.application.activity.TeamActivitySnapshot;
import io.crewscope.application.activity.TeamActivitySnapshotRequest;
import io.crewscope.application.activity.TeamRealtimeEventStore;
import io.crewscope.domain.activity.ActivityActor;
import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.ActivityPayloadSchema;
import io.crewscope.domain.activity.ActivityPayloadSchemaRef;
import io.crewscope.domain.activity.ActivityPublicPayload;
import io.crewscope.domain.activity.ActivityReference;
import io.crewscope.domain.activity.ActivityReferenceType;
import io.crewscope.domain.activity.ActivitySubject;
import io.crewscope.domain.activity.ActivitySubjectType;
import io.crewscope.domain.activity.ActivityVisibility;
import io.crewscope.domain.activity.TeamSequence;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
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
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL keyset reader for generation-bound Team Activity and realtime recovery. */
@Repository
public class JdbcActivityQueryAdapter implements ActivityQueryPort, TeamRealtimeEventStore {

    private static final String EVENT_SELECT = """
            SELECT event.organization_id, event.team_id, event.projection_name,
                   event.generation, event.activity_event_id, event.domain_event_id,
                   event.projection_schema_version, event.team_sequence, event.event_type,
                   event.category, event.visibility, event.subject_type, event.subject_id,
                   event.actor_type, event.actor_principal_id, event.occurred_at,
                   event.payload::TEXT AS payload, event.payload_schema_name,
                   event.payload_schema_version
            FROM crewscope.activity_event event
            WHERE event.organization_id = ? AND event.team_id = ?
              AND event.projection_name = ? AND event.generation = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Map<ActivityPayloadSchemaRef, ActivityPayloadSchema> payloadSchemas;

    public JdbcActivityQueryAdapter(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ActivityEventTypeRegistry registry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.payloadSchemas = indexSchemas(Objects.requireNonNull(registry, "registry"));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPage find(ActivityQuery query) {
        return read(query);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TeamActivitySnapshot snapshot(TeamActivitySnapshotRequest request) {
        TeamActivitySnapshotRequest value = Objects.requireNonNull(request, "request");
        ProjectionCoordinate coordinate = currentCoordinate(
                value.organizationId().value(),
                value.projectionName().value());
        var scope = io.crewscope.application.activity.ActivityCursorScope.of(
                value.organizationId(),
                value.teamId(),
                value.projectionName(),
                new ProjectionGeneration(coordinate.generation()),
                new SchemaVersion(coordinate.schemaVersion()),
                value.filter());
        Optional<HighWater> highWater = highWater(
                value.organizationId().value(),
                value.teamId().value(),
                value.projectionName().value(),
                coordinate.generation());
        List<ActivityEvent> events = highWater.isEmpty()
                ? List.of()
                : queryEvents(
                        scope,
                        value.filter(),
                        Optional.empty(),
                        Optional.of(highWater.orElseThrow()),
                        value.limit());
        boolean hasMore = events.size() > value.limit();
        List<ActivityEvent> page = hasMore ? events.subList(0, value.limit()) : events;
        Optional<TeamActivityCursor> snapshotCursor = highWater.map(position ->
                new TeamActivityCursor(
                        scope,
                        new TeamSequence(position.sequence()),
                        new ActivityEventId(position.eventId())));
        return new TeamActivitySnapshot(value, scope, page, snapshotCursor, hasMore);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPage read(ActivityQuery query) {
        ActivityQuery value = Objects.requireNonNull(query, "query");
        var scope = value.cursorScope();
        ProjectionCoordinate current = currentCoordinate(
                scope.organizationId().value(), scope.projectionName().value());
        if (current.generation() != scope.projectionGeneration().value()
                || current.schemaVersion() != scope.projectionSchemaVersion().value()) {
            throw new TeamActivityCursorExpiredException();
        }
        value.after().ifPresent(cursor -> requireRetained(scope, cursor));
        List<ActivityEvent> events = queryEvents(
                scope, value.filter(), value.after(), Optional.empty(), value.limit());
        boolean hasMore = events.size() > value.limit();
        return new ActivityPage(
                value, hasMore ? events.subList(0, value.limit()) : events, hasMore);
    }

    private ProjectionCoordinate currentCoordinate(UUID organizationId, String projectionName) {
        List<ProjectionCoordinate> rows = jdbc.query(
                """
                SELECT pointer.active_generation, definition.projection_schema_version
                FROM crewscope.projection_pointer pointer
                JOIN crewscope.projection_generation generation
                  ON generation.organization_id = pointer.organization_id
                 AND generation.projection_name = pointer.projection_name
                 AND generation.generation = pointer.active_generation
                 AND generation.status = 'ACTIVE'
                JOIN crewscope.projection_definition definition
                  ON definition.projection_name = generation.projection_name
                 AND definition.definition_version = generation.definition_version
                WHERE pointer.organization_id = ? AND pointer.projection_name = ?
                """,
                (row, ignored) -> new ProjectionCoordinate(
                        row.getLong("active_generation"),
                        row.getInt("projection_schema_version")),
                organizationId,
                projectionName);
        if (rows.size() != 1) {
            throw new TeamActivityCursorExpiredException();
        }
        return rows.get(0);
    }

    private Optional<HighWater> highWater(
            UUID organizationId, UUID teamId, String projectionName, long generation) {
        return jdbc.query(
                        """
                        SELECT team_sequence, activity_event_id
                        FROM crewscope.activity_event
                        WHERE organization_id = ? AND team_id = ?
                          AND projection_name = ? AND generation = ?
                        ORDER BY team_sequence DESC, activity_event_id DESC
                        LIMIT 1
                        """,
                        (row, ignored) -> new HighWater(
                                row.getLong("team_sequence"),
                                row.getObject("activity_event_id", UUID.class)),
                        organizationId,
                        teamId,
                        projectionName,
                        generation)
                .stream()
                .findFirst();
    }

    private void requireRetained(
            io.crewscope.application.activity.ActivityCursorScope scope,
            TeamActivityCursor cursor) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM crewscope.activity_event
                WHERE organization_id = ? AND team_id = ? AND projection_name = ?
                  AND generation = ? AND team_sequence = ? AND activity_event_id = ?
                """,
                Integer.class,
                scope.organizationId().value(),
                scope.teamId().value(),
                scope.projectionName().value(),
                scope.projectionGeneration().value(),
                cursor.teamSequence().value(),
                cursor.eventId().value());
        if (!Integer.valueOf(1).equals(count)) {
            throw new TeamActivityCursorExpiredException();
        }
    }

    private List<ActivityEvent> queryEvents(
            io.crewscope.application.activity.ActivityCursorScope scope,
            ActivityFilter filter,
            Optional<TeamActivityCursor> after,
            Optional<HighWater> highWater,
            int limit) {
        StringBuilder sql = new StringBuilder(EVENT_SELECT);
        List<Object> parameters = new ArrayList<>(List.of(
                scope.organizationId().value(),
                scope.teamId().value(),
                scope.projectionName().value(),
                scope.projectionGeneration().value()));
        appendFilter(sql, parameters, filter);
        after.ifPresent(cursor -> {
            sql.append(" AND (event.team_sequence, event.activity_event_id) > (?, ?)");
            parameters.add(cursor.teamSequence().value());
            parameters.add(cursor.eventId().value());
        });
        highWater.ifPresent(position -> {
            sql.append(" AND (event.team_sequence, event.activity_event_id) <= (?, ?)");
            parameters.add(position.sequence());
            parameters.add(position.eventId());
        });
        sql.append(" ORDER BY event.team_sequence, event.activity_event_id LIMIT ?");
        parameters.add(limit + 1);
        List<ActivityRow> rows = jdbc.query(sql.toString(), this::activityRow, parameters.toArray());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ActivityReference>> references = references(scope, rows);
        return rows.stream()
                .map(row -> row.toEvent(references.getOrDefault(row.eventId(), List.of())))
                .toList();
    }

    private void appendFilter(
            StringBuilder sql, List<Object> parameters, ActivityFilter filter) {
        filter.workItemId().ifPresent(workItemId -> {
            sql.append("""
                     AND (
                         (event.subject_type = 'WORK_ITEM' AND event.subject_id = ?)
                         OR EXISTS (
                             SELECT 1 FROM crewscope.activity_reference reference
                             WHERE reference.organization_id = event.organization_id
                               AND reference.projection_name = event.projection_name
                               AND reference.generation = event.generation
                               AND reference.activity_event_id = event.activity_event_id
                               AND reference.reference_type = 'WORK_ITEM'
                               AND reference.reference_id = ?
                         )
                     )
                    """);
            parameters.add(workItemId.value());
            parameters.add(workItemId.value());
        });
        appendIn(sql, parameters, "event.category", filter.categories().stream()
                .map(Enum::name).sorted().toList());
        appendIn(sql, parameters, "event.event_type", filter.eventTypes().stream()
                .map(EventType::value).sorted().toList());
        appendIn(sql, parameters, "event.actor_principal_id", filter.actorPrincipalIds().stream()
                .map(PrincipalId::value).sorted().toList());
    }

    private static void appendIn(
            StringBuilder sql, List<Object> parameters, String column, List<?> values) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append("?,".repeat(values.size()), 0, values.size() * 2 - 1)
                .append(')');
        parameters.addAll(values);
    }

    private Map<UUID, List<ActivityReference>> references(
            io.crewscope.application.activity.ActivityCursorScope scope,
            List<ActivityRow> rows) {
        String placeholders = rows.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(List.of(
                scope.organizationId().value(),
                scope.projectionName().value(),
                scope.projectionGeneration().value()));
        rows.forEach(row -> parameters.add(row.eventId()));
        List<ReferenceRow> references = jdbc.query(
                """
                SELECT activity_event_id, reference_type, reference_id
                FROM crewscope.activity_reference
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                  AND activity_event_id IN (""" + placeholders + ")"
                        + " ORDER BY activity_event_id, reference_order",
                (row, ignored) -> new ReferenceRow(
                        row.getObject("activity_event_id", UUID.class),
                        new ActivityReference(
                                ActivityReferenceType.valueOf(row.getString("reference_type")),
                                row.getObject("reference_id", UUID.class))),
                parameters.toArray());
        return references.stream().collect(Collectors.groupingBy(
                ReferenceRow::eventId, LinkedHashMap::new,
                Collectors.mapping(ReferenceRow::reference, Collectors.toList())));
    }

    private ActivityRow activityRow(ResultSet row, int ignored) throws SQLException {
        String schemaName = row.getString("payload_schema_name");
        SchemaVersion payloadVersion = new SchemaVersion(row.getInt("payload_schema_version"));
        ActivityPayloadSchema schema = payloadSchemas.get(
                new ActivityPayloadSchemaRef(schemaName, payloadVersion));
        if (schema == null) {
            throw new IllegalStateException("Stored Activity payload Schema is not registered");
        }
        ActivityPublicPayload payload = schema.createPayload(readStringMap(row.getString("payload")));
        UUID actorId = row.getObject("actor_principal_id", UUID.class);
        return new ActivityRow(
                row.getObject("activity_event_id", UUID.class),
                row.getObject("domain_event_id", UUID.class),
                new io.crewscope.domain.shared.id.OrganizationId(
                        row.getObject("organization_id", UUID.class)),
                new io.crewscope.domain.shared.id.TeamId(row.getObject("team_id", UUID.class)),
                new io.crewscope.domain.projection.ProjectionName(row.getString("projection_name")),
                new ProjectionGeneration(row.getLong("generation")),
                new SchemaVersion(row.getInt("projection_schema_version")),
                new TeamSequence(row.getLong("team_sequence")),
                EventType.from(row.getString("event_type")),
                ActivityCategory.valueOf(row.getString("category")),
                ActivityVisibility.valueOf(row.getString("visibility")),
                new ActivitySubject(
                        ActivitySubjectType.valueOf(row.getString("subject_type")),
                        row.getObject("subject_id", UUID.class)),
                new ActivityActor(
                        EventActorType.valueOf(row.getString("actor_type")),
                        Optional.ofNullable(actorId).map(PrincipalId::new)),
                UtcTimestamp.from(row.getObject("occurred_at", OffsetDateTime.class)),
                payload);
    }

    private Map<String, String> readStringMap(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Stored Activity payload must be a JSON object");
            }
            Map<String, String> values = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                if (!entry.getValue().isString()) {
                    throw new IllegalStateException(
                            "Stored Activity public payload values must be strings");
                }
                values.put(entry.getKey(), entry.getValue().stringValue());
            });
            return values;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored Activity payload is invalid", exception);
        }
    }

    private static Map<ActivityPayloadSchemaRef, ActivityPayloadSchema> indexSchemas(
            ActivityEventTypeRegistry registry) {
        Map<ActivityPayloadSchemaRef, ActivityPayloadSchema> schemas = new LinkedHashMap<>();
        for (ActivityEventTypeDefinition definition : registry.definitions()) {
            ActivityPayloadSchema candidate = definition.payloadSchema();
            ActivityPayloadSchema existing = schemas.putIfAbsent(candidate.reference(), candidate);
            if (existing != null
                    && (!existing.requiredFields().equals(candidate.requiredFields())
                            || !existing.optionalFields().equals(candidate.optionalFields()))) {
                throw new IllegalStateException("Activity Registry reuses a Schema with another shape");
            }
        }
        return Map.copyOf(schemas);
    }

    private record ProjectionCoordinate(long generation, int schemaVersion) {}

    private record HighWater(long sequence, UUID eventId) {}

    private record ReferenceRow(UUID eventId, ActivityReference reference) {}

    private record ActivityRow(
            UUID eventId,
            UUID domainEventId,
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            io.crewscope.domain.shared.id.TeamId teamId,
            io.crewscope.domain.projection.ProjectionName projectionName,
            ProjectionGeneration generation,
            SchemaVersion projectionSchemaVersion,
            TeamSequence teamSequence,
            EventType eventType,
            ActivityCategory category,
            ActivityVisibility visibility,
            ActivitySubject subject,
            ActivityActor actor,
            UtcTimestamp occurredAt,
            ActivityPublicPayload payload) {

        ActivityEvent toEvent(List<ActivityReference> references) {
            return new ActivityEvent(
                    new ActivityEventId(eventId),
                    domainEventId,
                    organizationId,
                    teamId,
                    projectionName,
                    generation,
                    projectionSchemaVersion,
                    teamSequence,
                    eventType,
                    category,
                    visibility,
                    subject,
                    actor,
                    references,
                    occurredAt,
                    payload);
        }
    }
}
