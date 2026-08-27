package io.crewscope.infrastructure.persistence.correlation;

import io.crewscope.application.audit.AuditEventTypeRegistry;
import io.crewscope.application.correlation.CorrelationCursor;
import io.crewscope.application.correlation.CorrelationEvent;
import io.crewscope.application.correlation.CorrelationEventSource;
import io.crewscope.application.correlation.CorrelationObjectReference;
import io.crewscope.application.correlation.CorrelationObjectType;
import io.crewscope.application.correlation.CorrelationPage;
import io.crewscope.application.correlation.CorrelationQuery;
import io.crewscope.application.correlation.CorrelationQueryPort;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL correlation reader. A page always uses one candidate query and at most one batch
 * enrichment query, so adding events cannot create N+1 reads.
 */
@Repository
public class JdbcCorrelationQueryAdapter implements CorrelationQueryPort {

    private static final Map<String, CorrelationObjectType> PAYLOAD_REFERENCE_FIELDS = Map.ofEntries(
            Map.entry("conversationId", CorrelationObjectType.CONVERSATION),
            Map.entry("sourceConversationId", CorrelationObjectType.CONVERSATION),
            Map.entry("workItemId", CorrelationObjectType.WORK_ITEM),
            Map.entry("taskId", CorrelationObjectType.TASK),
            Map.entry("reviewRequestId", CorrelationObjectType.REVIEW),
            Map.entry("sourceReviewRequestId", CorrelationObjectType.REVIEW),
            Map.entry("actionBundleId", CorrelationObjectType.ACTION),
            Map.entry("plannedActionId", CorrelationObjectType.ACTION));

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditEventTypeRegistry eventTypes;

    public JdbcCorrelationQueryAdapter(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AuditEventTypeRegistry eventTypes) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventTypes = Objects.requireNonNull(eventTypes, "eventTypes");
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CorrelationPage find(CorrelationQuery query) {
        CorrelationQuery request = Objects.requireNonNull(query, "query");
        List<RawEvent> candidates = readCandidates(request);
        boolean hasMore = candidates.size() > request.limit();
        List<RawEvent> page = hasMore
                ? List.copyOf(candidates.subList(0, request.limit()))
                : List.copyOf(candidates);
        Map<UUID, LinkedHashSet<CorrelationObjectReference>> references = baseReferences(page);
        enrich(request, page, references);

        List<CorrelationEvent> events = page.stream()
                .map(raw -> raw.toPublic(List.copyOf(references.get(raw.eventId()))))
                .toList();
        List<CorrelationPage.CorrelationObjectLink> objects = reverseLinks(events);
        Optional<CorrelationCursor> next = hasMore
                ? Optional.of(cursor(request, page.get(page.size() - 1)))
                : Optional.empty();
        return new CorrelationPage(request.correlationId(), events, objects, hasMore, next);
    }

    private List<RawEvent> readCandidates(CorrelationQuery query) {
        String cursorClause = query.after().isPresent()
                ? "AND (occurred_at, event_id, source) < (:afterAt, :afterId, :afterSource)"
                : "";
        String sql = """
                WITH candidates AS (
                    SELECT 'DOMAIN_EVENT' AS source, event.event_id,
                           event.event_id AS domain_event_id,
                           projected.event_id AS audit_object_id,
                           event.event_type, event.schema_version,
                           event.subject_type, event.subject_id,
                           event.actor_type, event.actor_id,
                           projected.outcome, event.occurred_at,
                           event.payload::TEXT AS payload
                    FROM crewscope.domain_event event
                    LEFT JOIN crewscope.audit_event projected
                      ON projected.organization_id = event.organization_id
                     AND projected.domain_event_id = event.event_id
                    WHERE event.organization_id = :organizationId
                      AND event.team_id = :teamId
                      AND event.correlation_id = :correlationId
                      AND event.event_type || '@' || event.schema_version IN (:coordinates)
                    UNION ALL
                    SELECT 'AUDIT' AS source, audit.event_id,
                           NULL::UUID AS domain_event_id,
                           audit.event_id AS audit_object_id,
                           audit.event_type, audit.schema_version,
                           audit.subject_type, audit.subject_id,
                           audit.actor_type, audit.actor_id,
                           audit.outcome, audit.occurred_at,
                           '{}'::TEXT AS payload
                    FROM crewscope.audit_event audit
                    WHERE audit.organization_id = :organizationId
                      AND audit.team_id = :teamId
                      AND audit.correlation_id = :correlationId
                      AND audit.domain_event_id IS NULL
                      AND audit.event_type || '@' || audit.schema_version IN (:coordinates)
                )
                SELECT source, event_id, domain_event_id, audit_object_id,
                       event_type, schema_version,
                       subject_type, subject_id, actor_type, actor_id, outcome,
                       occurred_at, payload
                FROM candidates
                WHERE TRUE
                %s
                ORDER BY occurred_at DESC, event_id DESC, source DESC
                LIMIT :limit
                """.formatted(cursorClause);
        MapSqlParameterSource parameters = baseParameters(query)
                .addValue("coordinates", reviewedCoordinates())
                .addValue("limit", query.limit() + 1);
        query.after().ifPresent(cursor -> parameters
                .addValue("afterAt", cursor.occurredAt().toOffsetDateTime())
                .addValue("afterId", cursor.eventId())
                .addValue("afterSource", cursor.source().name()));
        return jdbc.query(sql, parameters, this::mapRawEvent);
    }

    private void enrich(
            CorrelationQuery query,
            List<RawEvent> events,
            Map<UUID, LinkedHashSet<CorrelationObjectReference>> references) {
        Set<UUID> domainIds = new LinkedHashSet<>();
        Set<UUID> baseIds = new LinkedHashSet<>();
        events.forEach(event -> {
            event.domainEventId().ifPresent(domainIds::add);
            references.get(event.eventId()).forEach(reference -> baseIds.add(reference.id()));
        });
        if (domainIds.isEmpty() && baseIds.isEmpty()) {
            return;
        }

        List<String> sections = new ArrayList<>();
        MapSqlParameterSource parameters = baseParameters(query)
                .addValue("memberId", query.memberId().value());
        if (!domainIds.isEmpty()) {
            parameters.addValue("domainIds", domainIds);
            sections.add("""
                    SELECT 'ACTIVITY' AS object_type, activity.activity_event_id AS object_id,
                           activity.domain_event_id, NULL::UUID AS source_id
                    FROM crewscope.activity_event activity
                    JOIN crewscope.projection_pointer pointer
                      ON pointer.organization_id = activity.organization_id
                     AND pointer.projection_name = activity.projection_name
                     AND pointer.active_generation = activity.generation
                    WHERE activity.organization_id = :organizationId
                      AND activity.team_id = :teamId
                      AND activity.projection_name = 'team-activity'
                      AND activity.domain_event_id IN (:domainIds)
                    UNION ALL
                    SELECT reference.reference_type AS object_type, reference.reference_id,
                           activity.domain_event_id, NULL::UUID AS source_id
                    FROM crewscope.activity_event activity
                    JOIN crewscope.projection_pointer pointer
                      ON pointer.organization_id = activity.organization_id
                     AND pointer.projection_name = activity.projection_name
                     AND pointer.active_generation = activity.generation
                    JOIN crewscope.activity_reference reference
                      ON reference.organization_id = activity.organization_id
                     AND reference.projection_name = activity.projection_name
                     AND reference.generation = activity.generation
                     AND reference.activity_event_id = activity.activity_event_id
                    WHERE activity.organization_id = :organizationId
                      AND activity.team_id = :teamId
                      AND activity.projection_name = 'team-activity'
                      AND activity.domain_event_id IN (:domainIds)
                    """);
        }
        if (!baseIds.isEmpty()) {
            parameters.addValue("baseIds", baseIds);
            sections.add("""
                    SELECT 'INBOX' AS object_type, inbox.inbox_item_id AS object_id,
                           NULL::UUID AS domain_event_id, inbox.source_id
                    FROM crewscope.inbox_item inbox
                    JOIN crewscope.projection_pointer pointer
                      ON pointer.organization_id = inbox.organization_id
                     AND pointer.projection_name = inbox.projection_name
                     AND pointer.active_generation = inbox.generation
                    WHERE inbox.organization_id = :organizationId
                      AND inbox.team_id = :teamId
                      AND inbox.member_id = :memberId
                      AND inbox.projection_name = 'member-inbox'
                      AND inbox.source_id IN (:baseIds)
                    UNION ALL
                    SELECT 'NOTIFICATION' AS object_type, intent.intent_id AS object_id,
                           NULL::UUID AS domain_event_id, inbox.source_id
                    FROM crewscope.notification_intent intent
                    JOIN crewscope.inbox_item inbox
                      ON inbox.organization_id = intent.organization_id
                     AND inbox.projection_name = intent.projection_name
                     AND inbox.generation = intent.generation
                     AND inbox.inbox_item_id = intent.inbox_item_id
                    JOIN crewscope.projection_pointer pointer
                      ON pointer.organization_id = inbox.organization_id
                     AND pointer.projection_name = inbox.projection_name
                     AND pointer.active_generation = inbox.generation
                    WHERE intent.organization_id = :organizationId
                      AND intent.team_id = :teamId
                      AND intent.recipient_member_id = :memberId
                      AND inbox.source_id IN (:baseIds)
                    UNION ALL
                    SELECT 'PULL_REQUEST' AS object_type, result.id AS object_id,
                           NULL::UUID AS domain_event_id,
                           CASE WHEN result.action_id IN (:baseIds)
                                THEN result.action_id ELSE result.action_bundle_id END AS source_id
                    FROM crewscope.external_result result
                    WHERE result.organization_id = :organizationId
                      AND result.team_id = :teamId
                      AND result.external_object_type = 'PULL_REQUEST'
                      AND (result.action_id IN (:baseIds)
                           OR result.action_bundle_id IN (:baseIds))
                    """);
        }

        jdbc.query(String.join(" UNION ALL ", sections), parameters, resultSet -> {
            CorrelationObjectType type = publicObjectType(resultSet.getString("object_type"));
            if (type == null) {
                return;
            }
            CorrelationObjectReference reference = new CorrelationObjectReference(
                    type, resultSet.getObject("object_id", UUID.class));
            UUID domainEventId = resultSet.getObject("domain_event_id", UUID.class);
            UUID sourceId = resultSet.getObject("source_id", UUID.class);
            events.stream()
                    .filter(event -> domainEventId != null
                            && event.domainEventId().filter(domainEventId::equals).isPresent()
                            || sourceId != null && references.get(event.eventId()).stream()
                                    .anyMatch(value -> value.id().equals(sourceId)))
                    .forEach(event -> references.get(event.eventId()).add(reference));
        });
    }

    private Map<UUID, LinkedHashSet<CorrelationObjectReference>> baseReferences(
            List<RawEvent> events) {
        Map<UUID, LinkedHashSet<CorrelationObjectReference>> result = new LinkedHashMap<>();
        events.forEach(event -> {
            LinkedHashSet<CorrelationObjectReference> values = new LinkedHashSet<>();
            subjectReference(event.subjectType(), event.subjectId()).ifPresent(values::add);
            event.payload().forEach((field, value) -> {
                CorrelationObjectType type = PAYLOAD_REFERENCE_FIELDS.get(field);
                if (type != null && (value instanceof String || value instanceof UUID)) {
                    parseUuid(value.toString()).map(id -> new CorrelationObjectReference(type, id))
                            .ifPresent(values::add);
                }
            });
            event.auditObjectId().ifPresent(id -> values.add(
                    new CorrelationObjectReference(CorrelationObjectType.AUDIT, id)));
            result.put(event.eventId(), values);
        });
        return result;
    }

    private static List<CorrelationPage.CorrelationObjectLink> reverseLinks(
            List<CorrelationEvent> events) {
        Map<CorrelationObjectReference, LinkedHashSet<UUID>> links = new LinkedHashMap<>();
        events.forEach(event -> event.references().forEach(reference ->
                links.computeIfAbsent(reference, ignored -> new LinkedHashSet<>())
                        .add(event.eventId())));
        return links.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing((CorrelationObjectReference value) -> value.type().name())
                        .thenComparing(value -> value.id().toString())))
                .map(entry -> new CorrelationPage.CorrelationObjectLink(
                        entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private RawEvent mapRawEvent(ResultSet resultSet, int row) throws SQLException {
        String eventType = resultSet.getString("event_type");
        String schema = resultSet.getString("schema_version");
        // SQL already filters coordinates; this assertion prevents registry/query drift.
        if (eventTypes.find(EventType.from(eventType), SchemaVersion.from(schema)).isEmpty()) {
            throw new IllegalStateException("Correlation query admitted an unreviewed event schema");
        }
        return new RawEvent(
                resultSet.getObject("event_id", UUID.class),
                CorrelationEventSource.valueOf(resultSet.getString("source")),
                Optional.ofNullable(resultSet.getObject("domain_event_id", UUID.class)),
                Optional.ofNullable(resultSet.getObject("audit_object_id", UUID.class)),
                eventType,
                resultSet.getString("subject_type"),
                Optional.ofNullable(resultSet.getObject("subject_id", UUID.class)),
                resultSet.getString("actor_type"),
                Optional.ofNullable(resultSet.getObject("actor_id", UUID.class)),
                Optional.ofNullable(resultSet.getString("outcome")),
                UtcTimestamp.from(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                readPayload(resultSet.getString("payload")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String payload) {
        return new LinkedHashMap<>(objectMapper.readValue(payload, Map.class));
    }

    private List<String> reviewedCoordinates() {
        return eventTypes.definitions().stream()
                .map(definition -> definition.eventType().value() + '@'
                        + definition.sourceSchemaVersion().value())
                .distinct()
                .toList();
    }

    private static MapSqlParameterSource baseParameters(CorrelationQuery query) {
        return new MapSqlParameterSource()
                .addValue("organizationId", query.organizationId().value())
                .addValue("teamId", query.teamId().value())
                .addValue("correlationId", query.correlationId());
    }

    private static CorrelationCursor cursor(CorrelationQuery query, RawEvent event) {
        return new CorrelationCursor(
                query.organizationId(), query.teamId(), query.correlationId(),
                event.occurredAt(), event.eventId(), event.source());
    }

    private static Optional<CorrelationObjectReference> subjectReference(
            String subjectType, Optional<UUID> subjectId) {
        CorrelationObjectType type = switch (subjectType) {
            case "CONVERSATION" -> CorrelationObjectType.CONVERSATION;
            case "WORK_ITEM" -> CorrelationObjectType.WORK_ITEM;
            case "TASK" -> CorrelationObjectType.TASK;
            case "REVIEW", "REVIEW_REQUEST" -> CorrelationObjectType.REVIEW;
            case "ACTION", "ACTION_BUNDLE", "PLANNED_ACTION" ->
                    CorrelationObjectType.ACTION;
            default -> null;
        };
        return type == null ? Optional.empty()
                : subjectId.map(id -> new CorrelationObjectReference(type, id));
    }

    private static CorrelationObjectType publicObjectType(String type) {
        try {
            return CorrelationObjectType.valueOf(type);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private record RawEvent(
            UUID eventId,
            CorrelationEventSource source,
            Optional<UUID> domainEventId,
            Optional<UUID> auditObjectId,
            String eventType,
            String subjectType,
            Optional<UUID> subjectId,
            String actorType,
            Optional<UUID> actorId,
            Optional<String> outcome,
            UtcTimestamp occurredAt,
            Map<String, Object> payload) {

        private CorrelationEvent toPublic(List<CorrelationObjectReference> references) {
            return new CorrelationEvent(
                    eventId, source, eventType, actorType, actorId, outcome, occurredAt, references);
        }
    }
}
