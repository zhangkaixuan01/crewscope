package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.audit.AuditEventTypeDefinition;
import io.crewscope.application.audit.AuditEventTypeRegistry;
import io.crewscope.application.audit.AuditPayloadFieldMapping;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditRedactedSummary;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.projection.ProjectionCanonicalHash;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Append-only Audit projection with reviewed M3-M6 query summaries and safe fallback facts. */
@Component
public class AuditEventProjector implements ProjectionHandler {

    public static final String PROJECTION_NAME = "audit-event-v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditEventTypeRegistry registry;

    public AuditEventProjector(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AuditEventTypeRegistry registry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void project(ProjectionEvent event) {
        ProjectionEvent source = Objects.requireNonNull(event, "event");
        AuditDraft draft = preview(source);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, workspace_id,
                    principal_id, initiator_id, actor_type, actor_id, agent_principal_id,
                    credential_subject_type, credential_subject_id,
                    event_type, event_category, subject_type, subject_id, outcome,
                    authorization_context, domain_event_id,
                    provider_binding_id, connection_id, external_operation_hash,
                    correlation_id, causation_id, trace_id,
                    schema_version, retention_level, occurred_at, payload
                ) VALUES (
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    NULL, NULL,
                    ?, ?, ?, ?, ?,
                    CAST(? AS JSONB), ?,
                    ?, ?, ?,
                    ?, ?, NULL,
                    ?, ?, ?, CAST(? AS JSONB)
                )
                """,
                UUID.randomUUID(),
                source.organizationId(),
                source.teamId().orElse(null),
                source.workspaceId().orElse(null),
                draft.actorId(),
                draft.initiatorId(),
                source.actorType().name(),
                draft.actorId(),
                draft.agentId(),
                source.eventType(),
                draft.category().name(),
                source.aggregateType(),
                source.aggregateId(),
                draft.outcome().name(),
                draft.authorizationJson(),
                source.eventId(),
                draft.providerReference().map(ProviderReference::bindingId).orElse(null),
                draft.providerReference().map(ProviderReference::connectionId).orElse(null),
                draft.providerReference().flatMap(ProviderReference::externalOperationHash)
                        .orElse(null),
                source.correlationId(),
                source.causationId().orElse(null),
                source.schemaVersion(),
                draft.retentionLevel().name(),
                source.occurredAt().toOffsetDateTime(),
                draft.summaryJson());
    }

    /** Canonical validation source used to prove an append-only Audit replay remains equivalent. */
    public ProjectionSnapshot expectedSnapshot(UUID organizationId) {
        UUID organization = Objects.requireNonNull(organizationId, "organizationId");
        List<String> rows = jdbcTemplate.query(
                """
                SELECT event.event_id, event.event_type, event.schema_version,
                       event.organization_id, event.team_id, event.workspace_id,
                       event.subject_type, event.subject_id, event.aggregate_version,
                       event.actor_type, event.actor_id, event.correlation_id,
                       event.causation_id, event.occurred_at, event.payload::TEXT AS payload,
                       COALESCE(audit.authorization_context = '{}'::JSONB, FALSE)
                           AS legacy_audit_row
                FROM crewscope.domain_event event
                LEFT JOIN crewscope.audit_event audit
                  ON audit.organization_id = event.organization_id
                 AND audit.domain_event_id = event.event_id
                WHERE event.organization_id = ?
                ORDER BY event.event_id
                """,
                (resultSet, rowNumber) -> {
                    ProjectionEvent event = mapHistory(resultSet);
                    return resultSet.getBoolean("legacy_audit_row")
                            ? canonicalDraftRow(event, legacyDraft(event))
                            : canonicalDraftRow(event);
                },
                organization);
        return snapshot(rows);
    }

    /** Canonical current Audit snapshot; validation never mutates or replaces append-only rows. */
    public ProjectionSnapshot actualSnapshot(UUID organizationId) {
        UUID organization = Objects.requireNonNull(organizationId, "organizationId");
        List<String> rows = jdbcTemplate.query(
                """
                SELECT organization_id, team_id, workspace_id, principal_id, initiator_id,
                       actor_type, actor_id, agent_principal_id, event_type, event_category,
                       subject_type, subject_id, outcome, authorization_context::TEXT,
                       domain_event_id, provider_binding_id, connection_id,
                       external_operation_hash, correlation_id, causation_id, schema_version,
                       retention_level, occurred_at, payload::TEXT AS payload
                FROM crewscope.audit_event
                WHERE organization_id = ? AND domain_event_id IS NOT NULL
                ORDER BY domain_event_id
                """,
                (resultSet, rowNumber) -> canonicalStoredRow(resultSet),
                organization);
        return snapshot(rows);
    }

    /** Builds the exact safe fact before the append-only insert, also reused by M6-I01 mapping. */
    AuditDraft preview(ProjectionEvent event) {
        JsonNode payload = readPayload(event.payloadJson());
        Optional<AuditEventTypeDefinition> registered = registry.find(
                EventType.from(event.eventType()), SchemaVersion.from(event.schemaVersion()));
        Object actorId = event.actorId().orElse(null);
        Object initiatorId = event.actorType() == EventActorType.USER ? actorId : null;
        Object agentId = isAgent(event.actorType()) ? actorId : null;
        if (registered.isEmpty()) {
            return new AuditDraft(
                    AuditEventCategory.SYSTEM,
                    AuditOutcome.SUCCEEDED,
                    AuditRetentionLevel.STANDARD,
                    actorId,
                    initiatorId,
                    agentId,
                    Optional.empty(),
                    authorizationJson("UNREGISTERED", AuditOutcome.SUCCEEDED),
                    "{}");
        }

        AuditEventTypeDefinition definition = registered.orElseThrow();
        rejectUnknownFields(payload, definition.allowedSourceFields());
        Map<String, String> values = new LinkedHashMap<>();
        for (AuditPayloadFieldMapping field : definition.summaryFields()) {
            Optional<String> value = summaryValue(payload, field);
            value.ifPresent(candidate -> values.put(field.summaryField(), candidate));
        }
        AuditRedactedSummary summary = definition.projectSummary(values);
        AuditOutcome outcome = definition.resolveOutcome(
                definition.outcomeSourcePath().flatMap(path -> scalar(payload, path, false)));
        return new AuditDraft(
                definition.category(),
                outcome,
                definition.retentionLevel(),
                actorId,
                initiatorId,
                agentId,
                providerReference(event, payload),
                authorizationJson("REVIEWED", outcome),
                json(summary.values()));
    }

    private void rejectUnknownFields(JsonNode payload, Set<String> allowedFields) {
        List<String> unknown = new ArrayList<>();
        payload.properties().forEach(entry -> {
            if (!allowedFields.contains(entry.getKey())) {
                unknown.add(entry.getKey());
            }
        });
        if (!unknown.isEmpty()) {
            throw invalid("Registered Audit payload contains unreviewed fields");
        }
    }

    private Optional<String> summaryValue(JsonNode payload, AuditPayloadFieldMapping field) {
        JsonNode value = node(payload, field.sourcePath());
        if (value != null && value.isArray() && field.summaryField().endsWith("Count")) {
            return Optional.of(Integer.toString(value.size()));
        }
        return scalar(payload, field.sourcePath(), field.required());
    }

    private Optional<ProviderReference> providerReference(
            ProjectionEvent event, JsonNode payload) {
        Optional<UUID> directBinding = uuid(payload, "providerBindingId", false);
        Optional<UUID> directConnection = uuid(payload, "connectionId", false);
        Optional<String> directHash = scalar(payload, "externalOperationHash", false);
        if (directBinding.isPresent() || directConnection.isPresent() || directHash.isPresent()) {
            if (directBinding.isEmpty() || directConnection.isEmpty()) {
                throw invalid("Audit Provider references require Binding and Connection together");
            }
            return Optional.of(new ProviderReference(
                    directBinding.orElseThrow(),
                    directConnection.orElseThrow(),
                    directHash.map(AuditEventProjector::requireSha256)));
        }
        if (!event.aggregateType().equals("PROVIDER_BINDING")) {
            return Optional.empty();
        }
        List<UUID> connections = jdbcTemplate.query(
                """
                SELECT connection_id
                FROM crewscope.provider_binding
                WHERE organization_id = ? AND id = ? AND connection_id IS NOT NULL
                """,
                (resultSet, rowNumber) -> resultSet.getObject("connection_id", UUID.class),
                event.organizationId(),
                event.aggregateId());
        if (connections.isEmpty()) {
            return Optional.empty();
        }
        if (connections.size() != 1) {
            throw new IllegalStateException("Audit Provider Binding resolution was ambiguous");
        }
        return Optional.of(new ProviderReference(
                event.aggregateId(), connections.get(0), Optional.empty()));
    }

    private ProjectionEvent mapHistory(ResultSet resultSet) throws SQLException {
        return new ProjectionEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("schema_version"),
                resultSet.getObject("organization_id", UUID.class),
                Optional.ofNullable(resultSet.getObject("team_id", UUID.class)),
                Optional.ofNullable(resultSet.getObject("workspace_id", UUID.class)),
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getLong("aggregate_version"),
                EventActorType.valueOf(resultSet.getString("actor_type")),
                Optional.ofNullable(resultSet.getObject("actor_id", UUID.class)),
                resultSet.getObject("correlation_id", UUID.class),
                Optional.ofNullable(resultSet.getObject("causation_id", UUID.class)),
                UtcTimestamp.from(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                resultSet.getString("payload"));
    }

    private String canonicalDraftRow(ProjectionEvent event) {
        return canonicalDraftRow(event, preview(event));
    }

    private String canonicalDraftRow(ProjectionEvent event, AuditDraft draft) {
        return canonical(List.of(
                event.organizationId().toString(),
                event.teamId().map(UUID::toString).orElse(""),
                event.workspaceId().map(UUID::toString).orElse(""),
                Objects.toString(draft.actorId(), ""),
                Objects.toString(draft.initiatorId(), ""),
                event.actorType().name(),
                Objects.toString(draft.actorId(), ""),
                Objects.toString(draft.agentId(), ""),
                event.eventType(),
                draft.category().name(),
                event.aggregateType(),
                event.aggregateId().toString(),
                draft.outcome().name(),
                normalizedJson(draft.authorizationJson()),
                event.eventId().toString(),
                draft.providerReference().map(value -> value.bindingId().toString()).orElse(""),
                draft.providerReference().map(value -> value.connectionId().toString()).orElse(""),
                draft.providerReference().flatMap(ProviderReference::externalOperationHash)
                        .orElse(""),
                event.correlationId().toString(),
                event.causationId().map(UUID::toString).orElse(""),
                event.schemaVersion(),
                draft.retentionLevel().name(),
                event.occurredAt().toString(),
                normalizedJson(draft.summaryJson())));
    }

    private String canonicalStoredRow(ResultSet resultSet) throws SQLException {
        String authorization = resultSet.getString("authorization_context");
        boolean legacy = isLegacyAuthorization(authorization);
        return canonical(List.of(
                resultSet.getObject("organization_id", UUID.class).toString(),
                Objects.toString(resultSet.getObject("team_id", UUID.class), ""),
                Objects.toString(resultSet.getObject("workspace_id", UUID.class), ""),
                Objects.toString(resultSet.getObject("principal_id", UUID.class), ""),
                Objects.toString(resultSet.getObject("initiator_id", UUID.class), ""),
                resultSet.getString("actor_type"),
                Objects.toString(resultSet.getObject("actor_id", UUID.class), ""),
                Objects.toString(resultSet.getObject("agent_principal_id", UUID.class), ""),
                resultSet.getString("event_type"),
                resultSet.getString("event_category"),
                resultSet.getString("subject_type"),
                Objects.toString(resultSet.getObject("subject_id", UUID.class), ""),
                resultSet.getString("outcome"),
                legacy ? "{}" : normalizedJson(authorization),
                resultSet.getObject("domain_event_id", UUID.class).toString(),
                Objects.toString(resultSet.getObject("provider_binding_id", UUID.class), ""),
                Objects.toString(resultSet.getObject("connection_id", UUID.class), ""),
                Objects.toString(resultSet.getString("external_operation_hash"), ""),
                resultSet.getObject("correlation_id", UUID.class).toString(),
                Objects.toString(resultSet.getObject("causation_id", UUID.class), ""),
                resultSet.getString("schema_version"),
                resultSet.getString("retention_level"),
                UtcTimestamp.from(resultSet.getObject("occurred_at", OffsetDateTime.class)).toString(),
                legacy ? "{}" : normalizedJson(resultSet.getString("payload"))));
    }

    /**
     * Preserves pre-registry append-only facts without parsing or re-exposing their old payload.
     * V27 assigned these rows SYSTEM/STANDARD defaults and left authorization_context empty.
     */
    private AuditDraft legacyDraft(ProjectionEvent event) {
        Object actorId = event.actorId().orElse(null);
        return new AuditDraft(
                AuditEventCategory.SYSTEM,
                AuditOutcome.SUCCEEDED,
                AuditRetentionLevel.STANDARD,
                actorId,
                event.actorType() == EventActorType.USER ? actorId : null,
                isAgent(event.actorType()) ? actorId : null,
                Optional.empty(),
                "{}",
                "{}");
    }

    private boolean isLegacyAuthorization(String source) {
        try {
            JsonNode root = objectMapper.readTree(source);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Stored Audit authorization must be an object");
            }
            return root.isEmpty();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored Audit authorization is invalid", exception);
        }
    }

    private JsonNode readPayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload == null || !payload.isObject()) {
                throw invalid("Audit payload must be a JSON object");
            }
            return payload;
        } catch (InvalidProjectionEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException("Audit payload is not valid JSON", exception);
        }
    }

    private Optional<UUID> uuid(JsonNode root, String path, boolean required) {
        return scalar(root, path, required).map(value -> {
            try {
                UUID parsed = UUID.fromString(value);
                if (!parsed.toString().equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("not canonical");
                }
                return parsed;
            } catch (RuntimeException exception) {
                throw new InvalidProjectionEventException(
                        "Audit Provider reference must be a canonical UUID", exception);
            }
        });
    }

    private Optional<String> scalar(JsonNode root, String path, boolean required) {
        JsonNode value = node(root, path);
        while (value != null && value.isObject() && value.size() == 1 && value.get("value") != null) {
            value = value.get("value");
        }
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid("Registered Audit payload is missing a reviewed field");
            }
            return Optional.empty();
        }
        if (value.isString()) {
            return Optional.of(value.stringValue());
        }
        if (value.isIntegralNumber() || value.isFloatingPointNumber() || value.isBoolean()) {
            return Optional.of(value.toString());
        }
        throw invalid("Registered Audit summary field must be scalar");
    }

    private static JsonNode node(JsonNode root, String path) {
        JsonNode value = root;
        for (String segment : path.split("\\.")) {
            value = value == null ? null : value.get(segment);
        }
        return value;
    }

    private String authorizationJson(String classification, AuditOutcome outcome) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("classification", classification);
        root.put("result", outcome.name());
        return objectMapper.writeValueAsString(root);
    }

    private String json(Map<String, String> values) {
        return objectMapper.writeValueAsString(new TreeMap<>(values));
    }

    private String normalizedJson(String source) {
        try {
            JsonNode root = objectMapper.readTree(source);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Stored Audit JSON must be an object");
            }
            TreeMap<String, String> values = new TreeMap<>();
            root.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (value.isString()) {
                    values.put(entry.getKey(), value.stringValue());
                } else if (value.isIntegralNumber()
                        || value.isFloatingPointNumber()
                        || value.isBoolean()) {
                    values.put(entry.getKey(), value.toString());
                } else {
                    throw new IllegalStateException(
                            "Stored Audit JSON values must be scalar");
                }
            });
            return json(values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored Audit JSON is invalid", exception);
        }
    }

    private static String requireSha256(String value) {
        String normalized = Objects.requireNonNull(value, "externalOperationHash").strip();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw invalid("Audit external operation Hash must be lowercase SHA-256");
        }
        return normalized;
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
            throw new IllegalStateException("Audit canonical SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    private static boolean isAgent(EventActorType actorType) {
        return actorType == EventActorType.PERSONAL_AGENT
                || actorType == EventActorType.TEAM_AGENT
                || actorType == EventActorType.SPECIALIST_AGENT;
    }

    private static InvalidProjectionEventException invalid(String message) {
        return new InvalidProjectionEventException(message);
    }

    record ProviderReference(
            UUID bindingId, UUID connectionId, Optional<String> externalOperationHash) {

        ProviderReference {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(connectionId, "connectionId");
            externalOperationHash = Objects.requireNonNull(
                    externalOperationHash, "externalOperationHash");
        }
    }

    record AuditDraft(
            AuditEventCategory category,
            AuditOutcome outcome,
            AuditRetentionLevel retentionLevel,
            Object actorId,
            Object initiatorId,
            Object agentId,
            Optional<ProviderReference> providerReference,
            String authorizationJson,
            String summaryJson) {

        AuditDraft {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(retentionLevel, "retentionLevel");
            providerReference = Objects.requireNonNull(providerReference, "providerReference");
            Objects.requireNonNull(authorizationJson, "authorizationJson");
            Objects.requireNonNull(summaryJson, "summaryJson");
        }
    }
}
