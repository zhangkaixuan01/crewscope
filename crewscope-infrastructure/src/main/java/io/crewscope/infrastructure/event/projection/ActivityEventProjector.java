package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.activity.ActivityEventTypeDefinition;
import io.crewscope.application.activity.ActivityEventTypeRegistry;
import io.crewscope.application.activity.ActivityIdentitySource;
import io.crewscope.application.activity.ActivityPayloadFieldMapping;
import io.crewscope.application.activity.ActivityReferenceMapping;
import io.crewscope.domain.activity.ActivityActor;
import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.ActivityPublicPayload;
import io.crewscope.domain.activity.ActivityReference;
import io.crewscope.domain.activity.ActivitySubject;
import io.crewscope.domain.activity.TeamSequence;
import io.crewscope.domain.projection.ProjectionCanonicalHash;
import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Builds the generation-aware Team Activity read model from reviewed public event schemas. */
@Component
public class ActivityEventProjector implements GenerationAwareProjectionHandler {

    public static final ProjectionName PROJECTION_NAME = new ProjectionName("team-activity");
    public static final ProjectionDefinition DEFINITION = new ProjectionDefinition(
            PROJECTION_NAME,
            ProjectionDefinitionVersion.V1,
            SchemaVersion.V1,
            "activity.canonical-v1",
            "activity.expected-v1");

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityEventProjector.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ActivityEventTypeRegistry registry;

    public ActivityEventProjector(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ActivityEventTypeRegistry registry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public ProjectionDefinition definition() {
        return DEFINITION;
    }

    @Override
    public void project(ProjectionGenerationLease lease, ProjectionEvent event) {
        ProjectionGenerationLease target = Objects.requireNonNull(lease, "lease");
        ProjectionEvent source = Objects.requireNonNull(event, "event");
        Optional<ActivityDraft> draft = map(source, true);
        if (draft.isEmpty()) {
            return;
        }
        ActivityDraft activity = draft.orElseThrow();
        lockTeam(activity.organizationId(), activity.teamId());
        TeamSequence sequence = nextSequence(target, activity.teamId());
        ActivityEvent projected = activity.toEvent(target, sequence);
        insert(projected);
    }

    @Override
    public ProjectionSnapshot expectedSnapshot(OrganizationId organizationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        List<String> rows = jdbcTemplate.query(
                """
                SELECT event_id, event_type, schema_version, organization_id, team_id,
                       workspace_id, subject_type, subject_id, aggregate_version,
                       actor_type, actor_id, correlation_id, causation_id, occurred_at,
                       payload::TEXT AS payload
                FROM crewscope.domain_event
                WHERE organization_id = ?
                ORDER BY event_id
                """,
                (resultSet, rowNumber) -> mapHistory(resultSet),
                organization.value()).stream()
                .flatMap(Optional::stream)
                .map(this::canonicalDraftRow)
                .sorted()
                .toList();
        return snapshot(rows);
    }

    @Override
    public ProjectionSnapshot actualSnapshot(ProjectionGenerationKey generationKey) {
        ProjectionGenerationKey generation = Objects.requireNonNull(
                generationKey, "generationKey");
        Map<UUID, List<String>> references = loadReferences(generation);
        List<String> rows = jdbcTemplate.query(
                """
                SELECT activity_event_id, domain_event_id, organization_id, team_id,
                       projection_schema_version, event_type, category, visibility,
                       subject_type, subject_id, actor_type, actor_principal_id,
                       occurred_at, payload::TEXT AS payload,
                       payload_schema_name, payload_schema_version
                FROM crewscope.activity_event
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                ORDER BY activity_event_id
                """,
                (resultSet, rowNumber) -> canonicalStoredRow(
                        resultSet,
                        references.getOrDefault(
                                resultSet.getObject("activity_event_id", UUID.class), List.of())),
                generation.organizationId().value(),
                generation.projectionName().value(),
                generation.generation().value());
        return snapshot(rows);
    }

    private Optional<ActivityDraft> map(ProjectionEvent event, boolean warnUnknown) {
        EventType eventType = EventType.from(event.eventType());
        SchemaVersion schemaVersion = SchemaVersion.from(event.schemaVersion());
        Optional<ActivityEventTypeDefinition> registered = registry.find(eventType, schemaVersion);
        if (registered.isEmpty()) {
            if (warnUnknown) {
                LOGGER.warn(
                        "Ignoring unregistered Activity event type={} schemaVersion={} eventId={}",
                        eventType.value(), schemaVersion.value(), event.eventId());
            }
            return Optional.empty();
        }
        JsonNode payload = readPayload(event.payloadJson());
        ActivityEventTypeDefinition definition = registered.orElseThrow();
        Optional<UUID> teamScope = event.teamId();
        if (teamScope.isEmpty() && isValidNonTeamProviderEvent(definition, payload)) {
            if (warnUnknown) {
                LOGGER.warn(
                        "Ignoring registered Provider event outside Team scope type={} "
                                + "schemaVersion={} eventId={}",
                        eventType.value(), schemaVersion.value(), event.eventId());
            }
            return Optional.empty();
        }
        UUID teamId = teamScope.orElseThrow(() -> invalid(
                "Registered Activity event must carry a Team scope"));
        ActivitySubject subject = new ActivitySubject(
                definition.subjectType(), identity(definition.subjectSource(), event, payload, true)
                        .orElseThrow());
        List<ActivityReference> references = new ArrayList<>();
        for (ActivityReferenceMapping reference : definition.references()) {
            Optional<UUID> referenceId = identity(reference.source(), event, payload, reference.required());
            referenceId.ifPresent(id -> references.add(new ActivityReference(reference.type(), id)));
        }

        Map<String, String> publicValues = new LinkedHashMap<>();
        for (ActivityPayloadFieldMapping field : definition.payloadFields()) {
            Optional<String> value = scalar(payload, field.sourcePath(), field.required());
            value.ifPresent(text -> publicValues.put(field.publicField(), text));
        }
        ActivityPublicPayload publicPayload = definition.payloadSchema().createPayload(publicValues);
        ActivityActor actor = new ActivityActor(
                event.actorType(), event.actorId().map(PrincipalId::new));
        return Optional.of(new ActivityDraft(
                ActivityEventId.fromDomainEvent(event.eventId()),
                event.eventId(),
                new OrganizationId(event.organizationId()),
                new TeamId(teamId),
                eventType,
                definition,
                subject,
                actor,
                references,
                event.occurredAt(),
                publicPayload));
    }

    private boolean isValidNonTeamProviderEvent(
            ActivityEventTypeDefinition definition, JsonNode payload) {
        if (definition.category() != ActivityCategory.PROVIDER) {
            return false;
        }
        Optional<String> ownerType = scalar(payload, "ownerType", false);
        return ownerType.filter(value -> value.equals("USER") || value.equals("ORGANIZATION"))
                .isPresent();
    }

    private Optional<ActivityDraft> mapHistory(ResultSet resultSet) throws SQLException {
        ProjectionEvent event = new ProjectionEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("schema_version"),
                resultSet.getObject("organization_id", UUID.class),
                Optional.ofNullable(resultSet.getObject("team_id", UUID.class)),
                Optional.ofNullable(resultSet.getObject("workspace_id", UUID.class)),
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getLong("aggregate_version"),
                io.crewscope.domain.shared.event.EventActorType.valueOf(
                        resultSet.getString("actor_type")),
                Optional.ofNullable(resultSet.getObject("actor_id", UUID.class)),
                resultSet.getObject("correlation_id", UUID.class),
                Optional.ofNullable(resultSet.getObject("causation_id", UUID.class)),
                UtcTimestamp.from(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                resultSet.getString("payload"));
        return map(event, false);
    }

    private JsonNode readPayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload == null || !payload.isObject()) {
                throw invalid("Registered Activity payload must be a JSON object");
            }
            return payload;
        } catch (InvalidProjectionEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException(
                    "Registered Activity payload is not valid JSON", exception);
        }
    }

    private Optional<UUID> identity(
            ActivityIdentitySource source,
            ProjectionEvent event,
            JsonNode payload,
            boolean required) {
        return switch (source.kind()) {
            case TEAM -> event.teamId();
            case AGGREGATE -> Optional.of(event.aggregateId());
            case PAYLOAD -> uuid(payload, source.payloadPath().orElseThrow(), required);
        };
    }

    private Optional<UUID> uuid(JsonNode root, String path, boolean required) {
        Optional<String> scalar = scalar(root, path, required);
        if (scalar.isEmpty()) {
            return Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(scalar.orElseThrow());
            if (!parsed.toString().equalsIgnoreCase(scalar.orElseThrow())) {
                throw new IllegalArgumentException("not canonical");
            }
            return Optional.of(parsed);
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException(
                    "Registered Activity identity is not a canonical UUID", exception);
        }
    }

    private Optional<String> scalar(JsonNode root, String path, boolean required) {
        JsonNode value = root;
        for (String segment : path.split("\\.")) {
            value = value == null ? null : value.get(segment);
        }
        while (value != null && value.isObject() && value.size() == 1 && value.get("value") != null) {
            value = value.get("value");
        }
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid("Registered Activity payload is missing a reviewed field");
            }
            return Optional.empty();
        }
        if (value.isString()) {
            return Optional.of(value.stringValue());
        }
        if (value.isIntegralNumber() || value.isFloatingPointNumber() || value.isBoolean()) {
            return Optional.of(value.toString());
        }
        throw invalid("Registered Activity public field must be scalar");
    }

    private void lockTeam(OrganizationId organizationId, TeamId teamId) {
        List<UUID> locked = jdbcTemplate.query(
                """
                SELECT id FROM crewscope.team
                WHERE organization_id = ? AND id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                organizationId.value(),
                teamId.value());
        if (locked.size() != 1) {
            throw new IllegalStateException("Activity Team was not found");
        }
    }

    private TeamSequence nextSequence(ProjectionGenerationLease lease, TeamId teamId) {
        Long last = jdbcTemplate.queryForObject(
                """
                SELECT MAX(team_sequence)
                FROM crewscope.activity_event
                WHERE organization_id = ? AND team_id = ?
                  AND projection_name = ? AND generation = ?
                """,
                Long.class,
                lease.key().organizationId().value(),
                teamId.value(),
                lease.key().projectionName().value(),
                lease.key().generation().value());
        if (last == null) {
            return TeamSequence.FIRST;
        }
        return new TeamSequence(last).next();
    }

    private void insert(ActivityEvent event) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.activity_event (
                    organization_id, team_id, projection_name, generation,
                    activity_event_id, domain_event_id, projection_schema_version,
                    team_sequence, event_type, category, visibility, subject_type,
                    subject_id, actor_type, actor_principal_id, occurred_at, payload,
                    payload_schema_name, payload_schema_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
                """,
                event.organizationId().value(),
                event.teamId().value(),
                event.projectionName().value(),
                event.projectionGeneration().value(),
                event.id().value(),
                event.domainEventId(),
                event.projectionSchemaVersion().value(),
                event.teamSequence().value(),
                event.eventType().value(),
                event.category().name(),
                event.visibility().name(),
                event.subject().type().name(),
                event.subject().id(),
                event.actor().type().name(),
                event.actor().principalId().map(PrincipalId::value).orElse(null),
                event.occurredAt().toOffsetDateTime(),
                json(event.payload().values()),
                event.payload().schema().name(),
                event.payload().schema().version().value());
        for (int index = 0; index < event.references().size(); index++) {
            ActivityReference reference = event.references().get(index);
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.activity_reference (
                        organization_id, projection_name, generation, activity_event_id,
                        reference_order, reference_type, reference_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.organizationId().value(),
                    event.projectionName().value(),
                    event.projectionGeneration().value(),
                    event.id().value(),
                    index,
                    reference.type().name(),
                    reference.id());
        }
    }

    private Map<UUID, List<String>> loadReferences(ProjectionGenerationKey generation) {
        Map<UUID, List<String>> references = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT activity_event_id, reference_type, reference_id
                FROM crewscope.activity_reference
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                ORDER BY activity_event_id, reference_order
                """,
                (RowCallbackHandler) resultSet -> references
                        .computeIfAbsent(
                                resultSet.getObject("activity_event_id", UUID.class),
                                ignored -> new ArrayList<>())
                        .add(resultSet.getString("reference_type") + ":"
                                + resultSet.getObject("reference_id", UUID.class)),
                generation.organizationId().value(),
                generation.projectionName().value(),
                generation.generation().value());
        return references;
    }

    private String canonicalStoredRow(ResultSet resultSet, List<String> references)
            throws SQLException {
        return canonical(List.of(
                resultSet.getObject("activity_event_id", UUID.class).toString(),
                resultSet.getObject("domain_event_id", UUID.class).toString(),
                resultSet.getObject("organization_id", UUID.class).toString(),
                resultSet.getObject("team_id", UUID.class).toString(),
                Integer.toString(resultSet.getInt("projection_schema_version")),
                resultSet.getString("event_type"),
                resultSet.getString("category"),
                resultSet.getString("visibility"),
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class).toString(),
                resultSet.getString("actor_type"),
                Objects.toString(resultSet.getObject("actor_principal_id", UUID.class), ""),
                UtcTimestamp.from(resultSet.getObject("occurred_at", OffsetDateTime.class)).toString(),
                resultSet.getString("payload_schema_name"),
                Integer.toString(resultSet.getInt("payload_schema_version")),
                normalizedJson(resultSet.getString("payload")),
                String.join(",", references)));
    }

    private String canonicalDraftRow(ActivityDraft draft) {
        return canonical(List.of(
                draft.id().value().toString(),
                draft.domainEventId().toString(),
                draft.organizationId().value().toString(),
                draft.teamId().value().toString(),
                Integer.toString(DEFINITION.projectionSchemaVersion().value()),
                draft.eventType().value(),
                draft.definition().category().name(),
                draft.definition().visibility().name(),
                draft.subject().type().name(),
                draft.subject().id().toString(),
                draft.actor().type().name(),
                draft.actor().principalId()
                        .map(value -> value.value().toString())
                        .orElse(""),
                draft.occurredAt().toString(),
                draft.payload().schema().name(),
                Integer.toString(draft.payload().schema().version().value()),
                json(draft.payload().values()),
                draft.references().stream()
                        .map(reference -> reference.type().name() + ":" + reference.id())
                        .collect(java.util.stream.Collectors.joining(","))));
    }

    private String json(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(values));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Activity public payload could not be encoded", exception);
        }
    }

    private String normalizedJson(String source) {
        try {
            JsonNode root = objectMapper.readTree(source);
            TreeMap<String, String> values = new TreeMap<>();
            root.properties().forEach(entry -> values.put(
                    entry.getKey(), entry.getValue().stringValue()));
            return json(values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored Activity payload is invalid", exception);
        }
    }

    private static ProjectionSnapshot snapshot(List<String> canonicalRows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            canonicalRows.stream().sorted(Comparator.naturalOrder()).forEach(row -> {
                byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            });
            return new ProjectionSnapshot(
                    canonicalRows.size(),
                    new ProjectionCanonicalHash(HexFormat.of().formatHex(digest.digest())),
                    0,
                    List.of());
        } catch (Exception exception) {
            throw new IllegalStateException("Activity canonical SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    private static InvalidProjectionEventException invalid(String message) {
        return new InvalidProjectionEventException(message);
    }

    private record ActivityDraft(
            ActivityEventId id,
            UUID domainEventId,
            OrganizationId organizationId,
            TeamId teamId,
            EventType eventType,
            ActivityEventTypeDefinition definition,
            ActivitySubject subject,
            ActivityActor actor,
            List<ActivityReference> references,
            UtcTimestamp occurredAt,
            ActivityPublicPayload payload) {

        private ActivityDraft {
            references = List.copyOf(references);
        }

        private ActivityEvent toEvent(
                ProjectionGenerationLease lease, TeamSequence teamSequence) {
            return new ActivityEvent(
                    id,
                    domainEventId,
                    organizationId,
                    teamId,
                    lease.key().projectionName(),
                    lease.key().generation(),
                    DEFINITION.projectionSchemaVersion(),
                    teamSequence,
                    eventType,
                    definition.category(),
                    definition.visibility(),
                    subject,
                    actor,
                    references,
                    occurredAt,
                    payload);
        }

    }
}
