package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.audit.CrewScopeAuditEventTypes;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** PostgreSQL contract for the M6-E06 safe append-only Audit projector. */
@SpringBootTest(
        classes = AuditEventProjectorM6E06IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class AuditEventProjectorM6E06IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-26T02:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID organizationId;
    private UUID teamId;
    private UUID workspaceId;
    private UUID userId;
    private UUID agentId;
    private AuditEventProjector projector;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = UUID.randomUUID();
        teamId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        userId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Audit Org', 'ACTIVE')",
                organizationId);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Audit Team', 'ACTIVE')
                """,
                teamId,
                organizationId);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Audit Workspace', 'ACTIVE')
                """,
                workspaceId,
                organizationId,
                teamId);
        insertPrincipal(userId, "USER", "Audit User");
        insertPrincipal(agentId, "SPECIALIST_AGENT", "Audit Agent");
        projector = new AuditEventProjector(
                jdbcTemplate, objectMapper, CrewScopeAuditEventTypes.reviewedRegistry());
    }

    @Test
    void persistsAgentIdentityFailureOutcomeAndOnlyTheReviewedSummary() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskExecutionId", UUID.randomUUID().toString());
        payload.put("attempt", 2);
        payload.put("agentRunId", UUID.randomUUID().toString());
        payload.put("segmentSequence", 3);
        payload.put("eventSequence", 8);
        payload.put("eventKind", "RUN_ERROR");
        payload.put("runtimeOccurredAt", BASE_TIME.toString());
        payload.put("safeText", "Authorization: Bearer must-never-be-copied");
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("category", "MODEL_EXECUTION_FAILED");
        failure.put("retryable", true);
        failure.put("safeMessage", "Provider request failed");
        failure.put("runtimeCode", "MODEL_RATE_LIMITED");
        payload.set("failure", failure);
        ProjectionEvent event = event(
                "AGENT_RUN_EVENT_RECORDED",
                "TASK_EXECUTION",
                UUID.randomUUID(),
                EventActorType.SPECIALIST_AGENT,
                agentId,
                payload,
                Optional.of(UUID.randomUUID()));

        persistAndProject(event);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT initiator_id, actor_id, agent_principal_id, event_category, outcome,
                       retention_level, authorization_context::TEXT AS authorization_context,
                       payload::TEXT AS payload
                FROM crewscope.audit_event WHERE domain_event_id = ?
                """,
                event.eventId());
        assertNull(row.get("initiator_id"));
        assertEquals(agentId, row.get("actor_id"));
        assertEquals(agentId, row.get("agent_principal_id"));
        assertEquals("EXECUTION", row.get("event_category"));
        assertEquals("FAILED", row.get("outcome"));
        assertEquals("STANDARD", row.get("retention_level"));
        JsonNode summary = objectMapper.readTree((String) row.get("payload"));
        assertEquals("RUN_ERROR", summary.get("eventKind").stringValue());
        assertEquals("MODEL_RATE_LIMITED", summary.get("runtimeCode").stringValue());
        assertFalse(summary.has("safeText"));
        assertFalse(((String) row.get("payload")).contains("must-never-be-copied"));
        assertEquals(
                "REVIEWED",
                objectMapper.readTree((String) row.get("authorization_context"))
                        .get("classification")
                        .stringValue());
    }

    @Test
    void persistsUserInitiatorCorrelationAndFailedActionOutcome() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("actionReceiptId", UUID.randomUUID().toString());
        payload.put("actionBundleId", UUID.randomUUID().toString());
        payload.put("plannedActionId", UUID.randomUUID().toString());
        payload.put("actionDigest", "a".repeat(64));
        payload.put("result", "FAILED");
        payload.put("source", "PROVIDER");
        payload.put("evidenceCode", "GITHUB_RATE_LIMITED");
        payload.put("evidenceHash", "b".repeat(64));
        UUID causationId = UUID.randomUUID();
        ProjectionEvent event = event(
                "ACTION_RECEIPT_RECORDED",
                "ACTION_RECEIPT",
                UUID.randomUUID(),
                EventActorType.USER,
                userId,
                payload,
                Optional.of(causationId));

        persistAndProject(event);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT initiator_id, actor_id, agent_principal_id, event_category, outcome,
                       correlation_id, causation_id, domain_event_id,
                       payload::TEXT AS payload
                FROM crewscope.audit_event WHERE domain_event_id = ?
                """,
                event.eventId());
        assertEquals(userId, row.get("initiator_id"));
        assertEquals(userId, row.get("actor_id"));
        assertNull(row.get("agent_principal_id"));
        assertEquals("ACTION", row.get("event_category"));
        assertEquals("FAILED", row.get("outcome"));
        assertEquals(event.correlationId(), row.get("correlation_id"));
        assertEquals(causationId, row.get("causation_id"));
        assertEquals(event.eventId(), row.get("domain_event_id"));
        JsonNode summary = objectMapper.readTree((String) row.get("payload"));
        assertEquals("GITHUB_RATE_LIMITED", summary.get("evidenceCode").stringValue());
        assertFalse(summary.has("actionDigest"));
    }

    @Test
    void resolvesDirectLarkProviderCoordinatesWithoutExternalContent() {
        UUID bindingId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String operationHash = "c".repeat(64);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("memberMappingId", UUID.randomUUID().toString());
        payload.put("teamMemberId", UUID.randomUUID().toString());
        payload.put("status", "ACTIVE");
        payload.put("providerBindingId", bindingId.toString());
        payload.put("connectionId", connectionId.toString());
        payload.put("externalOperationHash", operationHash);
        ProjectionEvent event = event(
                "LARK_MEMBER_MAPPING_CONFIRMED",
                "LARK_MEMBER_MAPPING",
                UUID.randomUUID(),
                EventActorType.USER,
                userId,
                payload,
                Optional.empty());

        AuditEventProjector.AuditDraft draft = projector.preview(event);

        assertEquals("SECURITY", draft.category().name());
        AuditEventProjector.ProviderReference provider =
                draft.providerReference().orElseThrow();
        assertEquals(bindingId, provider.bindingId());
        assertEquals(connectionId, provider.connectionId());
        assertEquals(operationHash, provider.externalOperationHash().orElseThrow());
        assertFalse(draft.summaryJson().contains("memberMappingId"));
    }

    @Test
    void acceptsModelConnectionLifecycleWithoutInventingAProviderBindingReference() {
        UUID connectionId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("connectionId", connectionId.toString());
        payload.put("operation", "CREATED");
        payload.put("providerKey", "deepseek");
        payload.put("credentialSecretVersion", 0);
        payload.put("connectionStatus", "ACTIVE");
        payload.putNull("failureCode");
        ProjectionEvent event = event(
                "MODEL_CONNECTION_CREATED",
                "MODEL_CONNECTION",
                connectionId,
                EventActorType.USER,
                userId,
                payload,
                Optional.empty());

        persistAndProject(event);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT event_category, provider_binding_id, connection_id, payload::TEXT AS payload
                FROM crewscope.audit_event WHERE domain_event_id = ?
                """,
                event.eventId());
        assertEquals("MODEL", row.get("event_category"));
        assertNull(row.get("provider_binding_id"));
        assertNull(row.get("connection_id"));
        JsonNode summary = objectMapper.readTree((String) row.get("payload"));
        assertEquals("deepseek", summary.get("providerKey").stringValue());
        assertFalse(summary.has("credentialSecretVersion"));
    }

    @Test
    void failsClosedForUnknownFieldsInARegisteredPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("agentProfileId", UUID.randomUUID().toString());
        payload.put("agentPrincipalId", agentId.toString());
        payload.put("ownershipType", "TEAM");
        payload.put("ownershipId", teamId.toString());
        payload.put("runtimeRole", "CODING_SPECIALIST");
        payload.put("templateKey", "coding-specialist");
        payload.put("templateVersion", 1);
        payload.put("status", "ACTIVE");
        payload.put("version", 0);
        payload.put("accessToken", "secret-probe");
        ProjectionEvent event = event(
                "AGENT_PROFILE_CREATED",
                "AGENT_PROFILE",
                UUID.randomUUID(),
                EventActorType.USER,
                userId,
                payload,
                Optional.empty());
        insertDomainEvent(event);

        assertThrows(
                InvalidProjectionEventException.class,
                () -> transaction().executeWithoutResult(ignored -> projector.project(event)));
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.audit_event WHERE domain_event_id = ?",
                        Integer.class,
                        event.eventId()));
    }

    @Test
    void unregisteredEventsKeepAnAppendOnlyFactWithoutCopyingRawPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("authorization", "Bearer must-never-persist");
        payload.put("email", "private@example.com");
        ProjectionEvent event = event(
                "FUTURE_UNREGISTERED_EVENT",
                "SECURITY_PROBE",
                UUID.randomUUID(),
                EventActorType.USER,
                userId,
                payload,
                Optional.empty());

        persistAndProject(event);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT event_category, retention_level,
                       authorization_context::TEXT AS authorization_context,
                       payload::TEXT AS payload
                FROM crewscope.audit_event WHERE domain_event_id = ?
                """,
                event.eventId());
        assertEquals("SYSTEM", row.get("event_category"));
        assertEquals("STANDARD", row.get("retention_level"));
        assertEquals(0, objectMapper.readTree((String) row.get("payload")).size());
        assertFalse(((String) row.get("payload")).contains("private@example.com"));
        assertEquals(
                "UNREGISTERED",
                objectMapper.readTree((String) row.get("authorization_context"))
                        .get("classification")
                        .stringValue());
    }

    @Test
    void canonicalValidationMatchesDomainHistoryAndAppendOnlyAuditRows() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("actionReceiptId", UUID.randomUUID().toString());
        payload.put("actionBundleId", UUID.randomUUID().toString());
        payload.put("plannedActionId", UUID.randomUUID().toString());
        payload.put("actionDigest", "d".repeat(64));
        payload.put("result", "SUCCEEDED");
        payload.put("source", "PROVIDER");
        payload.put("evidenceCode", "GITHUB_ACCEPTED");
        payload.put("evidenceHash", "e".repeat(64));
        persistAndProject(event(
                "ACTION_RECEIPT_RECORDED",
                "ACTION_RECEIPT",
                UUID.randomUUID(),
                EventActorType.USER,
                userId,
                payload,
                Optional.empty()));

        ProjectionSnapshot expected = projector.expectedSnapshot(organizationId);
        ProjectionSnapshot actual = projector.actualSnapshot(organizationId);

        assertEquals(1, expected.rowCount());
        assertEquals(expected, actual);
        assertTrue(actual.healthy());
        assertNotNull(actual.canonicalHash());
    }

    @Test
    void legacyRowsRemainImmutableAndValidateAsEmptySafeSummaries() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("result", "SUCCEEDED");
        ObjectNode legacyBody = objectMapper.createObjectNode();
        legacyBody.put("authorization", "Bearer legacy-secret-must-not-be-parsed");
        payload.set("providerBody", legacyBody);
        ProjectionEvent event = event(
                "FUTURE_LEGACY_EVENT",
                "LEGACY_AUDIT",
                UUID.randomUUID(),
                EventActorType.USER,
                userId,
                payload,
                Optional.empty());
        insertDomainEvent(event);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, workspace_id,
                    principal_id, initiator_id, actor_type, actor_id, agent_principal_id,
                    event_type, subject_type, subject_id, outcome,
                    authorization_context, domain_event_id, correlation_id, causation_id,
                    schema_version, occurred_at, payload
                ) VALUES (?, ?, ?, ?, ?, ?, 'USER', ?, NULL, ?, ?, ?, 'SUCCEEDED',
                          '{}'::JSONB, ?, ?, NULL, ?, ?, CAST(? AS JSONB))
                """,
                UUID.randomUUID(),
                organizationId,
                teamId,
                workspaceId,
                userId,
                userId,
                userId,
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventId(),
                event.correlationId(),
                event.schemaVersion(),
                event.occurredAt().toOffsetDateTime(),
                event.payloadJson());

        ProjectionSnapshot expected = projector.expectedSnapshot(organizationId);
        ProjectionSnapshot actual = projector.actualSnapshot(organizationId);

        assertEquals(expected, actual);
        assertEquals(1, actual.rowCount());
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT payload::TEXT FROM crewscope.audit_event WHERE domain_event_id = ?",
                String.class,
                event.eventId()).contains("legacy-secret-must-not-be-parsed"));
    }

    private void persistAndProject(ProjectionEvent event) {
        insertDomainEvent(event);
        transaction().executeWithoutResult(ignored -> projector.project(event));
    }

    private void insertDomainEvent(ProjectionEvent event) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, team_id, workspace_id,
                    subject_type, subject_id, aggregate_version,
                    actor_type, actor_id, correlation_id, causation_id,
                    occurred_at, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                """,
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.organizationId(),
                event.teamId().orElse(null),
                event.workspaceId().orElse(null),
                event.aggregateType(),
                event.aggregateId(),
                event.actorType().name(),
                event.actorId().orElse(null),
                event.correlationId(),
                event.causationId().orElse(null),
                event.occurredAt().toOffsetDateTime(),
                event.payloadJson());
    }

    private ProjectionEvent event(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            EventActorType actorType,
            UUID actorId,
            ObjectNode payload,
            Optional<UUID> causationId) {
        return new ProjectionEvent(
                UUID.randomUUID(),
                eventType,
                "1",
                organizationId,
                Optional.of(teamId),
                Optional.of(workspaceId),
                aggregateType,
                aggregateId,
                0,
                actorType,
                Optional.of(actorId),
                UUID.randomUUID(),
                causationId,
                UtcTimestamp.from(BASE_TIME),
                objectMapper.writeValueAsString(payload));
    }

    private void insertPrincipal(UUID id, String type, String displayName) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                id,
                organizationId,
                type,
                displayName);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
