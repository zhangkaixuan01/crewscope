package io.crewscope.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.ActivityPage;
import io.crewscope.application.activity.ActivityQuery;
import io.crewscope.application.activity.CrewScopeActivityEventTypes;
import io.crewscope.application.activity.TeamActivityCursorExpiredException;
import io.crewscope.application.audit.AuditAccessRecord;
import io.crewscope.application.audit.AuditPage;
import io.crewscope.application.audit.AuditQuery;
import io.crewscope.application.audit.AuditQueryFilter;
import io.crewscope.application.audit.CrewScopeAuditEventTypes;
import io.crewscope.application.correlation.CorrelationObjectType;
import io.crewscope.application.correlation.CorrelationPage;
import io.crewscope.application.correlation.CorrelationQuery;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.event.projection.ActivityEventProjector;
import io.crewscope.infrastructure.persistence.activity.JdbcActivityQueryAdapter;
import io.crewscope.infrastructure.persistence.audit.JdbcAuditAccessRecorder;
import io.crewscope.infrastructure.persistence.audit.JdbcAuditQueryAdapter;
import io.crewscope.infrastructure.persistence.correlation.JdbcCorrelationQueryAdapter;
import io.crewscope.infrastructure.persistence.operations.JdbcOperationsHealthQueryAdapter;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL keyset, Generation and tenant-isolation contract for M6-I01 readers. */
@SpringBootTest(
        classes = JdbcQueryAdaptersM6I01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class JdbcQueryAdaptersM6I01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-26T06:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private TeamId teamId;
    private UUID principalId;
    private JdbcActivityQueryAdapter activity;
    private JdbcAuditQueryAdapter audit;
    private JdbcOperationsHealthQueryAdapter operations;
    private JdbcCorrelationQueryAdapter correlations;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                ActivityEventProjector.PROJECTION_NAME.value());
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        principalId = UUID.randomUUID();
        seedScope(organizationId.value(), teamId.value(), principalId, "Primary");
        bootstrapActivityProjection();
        activity = new JdbcActivityQueryAdapter(
                jdbc, objectMapper, CrewScopeActivityEventTypes.reviewedRegistry());
        audit = new JdbcAuditQueryAdapter(
                jdbc, objectMapper, CrewScopeAuditEventTypes.reviewedRegistry());
        operations = new JdbcOperationsHealthQueryAdapter(jdbc);
        correlations = new JdbcCorrelationQueryAdapter(
                new NamedParameterJdbcTemplate(jdbc), objectMapper,
                CrewScopeAuditEventTypes.reviewedRegistry());
    }

    @Test
    void correlationUsesAReviewedKeysetAndBuildsBidirectionalActivityLinks() {
        UUID correlationId = UUID.randomUUID();
        seedActivity(1, BASE_TIME, "CORR-1", correlationId);
        seedActivity(2, BASE_TIME.plusSeconds(1), "CORR-2", correlationId);
        seedActivity(3, BASE_TIME.plusSeconds(2), "CORR-3", correlationId);
        jdbc.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    subject_type, subject_id, aggregate_version, actor_type, actor_id,
                    correlation_id, occurred_at, payload
                ) VALUES (?, 'FUTURE_CREDENTIAL_EXPOSED', '1', ?, ?, 'WORK_ITEM', ?, 0,
                          'USER', ?, ?, ?, '{"credential":"never-public"}'::JSONB)
                """,
                UUID.randomUUID(), organizationId.value(), teamId.value(), UUID.randomUUID(),
                principalId, correlationId,
                BASE_TIME.plusSeconds(3).atOffset(ZoneOffset.UTC));
        CorrelationQuery firstQuery = new CorrelationQuery(
                organizationId, teamId,
                io.crewscope.domain.team.TeamMemberId.generate(), correlationId,
                Optional.empty(), 2);

        CorrelationPage first = correlations.find(firstQuery);

        assertEquals(2, first.events().size());
        assertTrue(first.hasMore());
        assertTrue(first.events().stream()
                .noneMatch(event -> event.eventType().equals("FUTURE_CREDENTIAL_EXPOSED")));
        assertTrue(first.events().stream().allMatch(event -> event.references().stream()
                .anyMatch(reference -> reference.type() == CorrelationObjectType.ACTIVITY)));
        assertTrue(first.objects().stream().allMatch(object -> object.eventIds().stream()
                .allMatch(eventId -> first.events().stream()
                        .anyMatch(event -> event.eventId().equals(eventId)))));

        CorrelationPage second = correlations.find(new CorrelationQuery(
                organizationId, teamId, firstQuery.memberId(), correlationId,
                first.nextCursor(), 2));
        assertEquals(1, second.events().size());
        assertFalse(second.hasMore());
    }

    @Test
    void activityUsesStableKeysetAndRejectsRetiredGeneration() {
        UUID firstDomainEvent = seedActivity(1, BASE_TIME, "OPS-1");
        UUID secondDomainEvent = seedActivity(2, BASE_TIME, "OPS-2");
        seedActivity(3, BASE_TIME.plusSeconds(1), "OPS-3");
        ActivityQuery firstQuery = ActivityQuery.team(
                organizationId, teamId, ActivityEventProjector.PROJECTION_NAME,
                ProjectionGeneration.FIRST, SchemaVersion.V1,
                ActivityFilter.ALL, Optional.empty(), 2);

        ActivityPage first = activity.find(firstQuery);

        assertEquals(List.of(
                        ActivityEventId.fromDomainEvent(firstDomainEvent),
                        ActivityEventId.fromDomainEvent(secondDomainEvent)),
                first.events().stream().map(event -> event.id()).toList());
        assertTrue(first.hasMore());
        ActivityPage second = activity.find(ActivityQuery.team(
                organizationId, teamId, ActivityEventProjector.PROJECTION_NAME,
                ProjectionGeneration.FIRST, SchemaVersion.V1,
                ActivityFilter.ALL, first.nextCursor(), 2));
        assertEquals(1, second.events().size());
        assertFalse(second.hasMore());

        switchToEmptyGenerationTwo();

        assertThrows(TeamActivityCursorExpiredException.class, () -> activity.find(firstQuery));
    }

    @Test
    void activityQueryNeverCrossesOrganizationOrTeamScope() {
        seedActivity(1, BASE_TIME, "OPS-1");
        OrganizationId otherOrganization = OrganizationId.generate();
        TeamId otherTeam = TeamId.generate();
        seedScope(otherOrganization.value(), otherTeam.value(), UUID.randomUUID(), "Other");

        ActivityPage page = activity.find(ActivityQuery.team(
                organizationId, teamId, ActivityEventProjector.PROJECTION_NAME,
                ProjectionGeneration.FIRST, SchemaVersion.V1,
                ActivityFilter.ALL, Optional.empty(), 20));

        assertEquals(1, page.events().size());
        assertTrue(page.events().stream()
                .allMatch(event -> event.organizationId().equals(organizationId)
                        && event.teamId().equals(teamId)));
    }

    @Test
    void activityDetailReadsOnlyTheCurrentGenerationAndExactTenantScope() {
        UUID domainEventId = seedActivity(1, BASE_TIME, "OPS-DETAIL");
        ActivityEventId eventId = ActivityEventId.fromDomainEvent(domainEventId);

        assertEquals(
                eventId,
                activity.findCurrentById(organizationId, teamId, eventId)
                        .orElseThrow()
                        .id());
        assertTrue(activity.findCurrentById(
                        organizationId, TeamId.generate(), eventId)
                .isEmpty());
        assertTrue(activity.findCurrentById(
                        OrganizationId.generate(), teamId, eventId)
                .isEmpty());

        switchToEmptyGenerationTwo();

        assertTrue(activity.findCurrentById(organizationId, teamId, eventId).isEmpty());
    }

    @Test
    void auditUsesPostgresUuidTieBreakAndReturnsEmptyUnregisteredSummary() {
        UUID smaller = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID larger = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        seedAudit(smaller, "UNREGISTERED_EVENT", "{}", "{}", BASE_TIME);
        seedAudit(larger, "UNREGISTERED_EVENT", "{}", "{}", BASE_TIME);
        AuditQuery firstQuery = AuditQuery.create(
                organizationId, teamId, AuditQueryFilter.ALL, Optional.empty(), 1);

        AuditPage first = audit.find(firstQuery);
        AuditPage second = audit.find(AuditQuery.create(
                organizationId, teamId, AuditQueryFilter.ALL, first.nextCursor(), 1));

        assertEquals(larger, first.events().get(0).id().value());
        assertEquals(smaller, second.events().get(0).id().value());
        assertTrue(first.events().get(0).summary().values().isEmpty());
    }

    @Test
    void auditExplorerAccessIsAppendOnlySafeAndTeamScoped() {
        Principal actor = Principal.create(
                new PrincipalId(principalId),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Primary User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                UtcTimestamp.from(BASE_TIME));
        UUID correlationId = UUID.randomUUID();
        JdbcAuditAccessRecorder recorder = new JdbcAuditAccessRecorder(jdbc, objectMapper);
        recorder.record(new AuditAccessRecord(
                AuditAccessRecord.Operation.QUERY,
                organizationId,
                teamId,
                actor,
                correlationId,
                AuditOutcome.SUCCEEDED,
                7,
                UtcTimestamp.from(BASE_TIME)));
        TeamId otherTeam = TeamId.generate();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'Other Team', 'ACTIVE')",
                otherTeam.value(), organizationId.value());
        recorder.record(new AuditAccessRecord(
                AuditAccessRecord.Operation.EXPORT,
                organizationId,
                otherTeam,
                actor,
                UUID.randomUUID(),
                AuditOutcome.SUCCEEDED,
                3,
                UtcTimestamp.from(BASE_TIME.plusSeconds(1))));
        AuditQueryFilter filter = new AuditQueryFilter(
                Optional.empty(),
                Optional.empty(),
                Set.of(AuditEventCategory.SECURITY),
                Set.of(AuditOutcome.SUCCEEDED),
                Set.of(),
                Set.of(new PrincipalId(principalId)),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(correlationId));

        AuditPage page = audit.find(AuditQuery.create(
                organizationId, teamId, filter, Optional.empty(), 20));

        assertEquals(1, page.events().size());
        var event = page.events().get(0);
        assertEquals("AUDIT_EXPLORER_QUERIED", event.summary().eventType().value());
        assertEquals(AuditEventCategory.SECURITY, event.category());
        assertEquals(AuditOutcome.SUCCEEDED, event.outcome());
        assertEquals(correlationId, event.correlation().correlationId());
        assertEquals(
                Map.of("operation", "QUERY", "result", "SUCCEEDED", "rowCount", "7"),
                event.summary().values());
        assertEquals(teamId, event.teamId());
        assertEquals(
                3L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.audit_event, "
                                + "LATERAL jsonb_object_keys(payload) "
                                + "WHERE correlation_id = ?",
                        Long.class,
                        correlationId));
    }

    @Test
    void operationsReturnsFiveSafeComponentsForOnlyTheRequestedOrganization() {
        UUID foreignOrganization = UUID.randomUUID();
        UUID foreignTeam = UUID.randomUUID();
        seedScope(foreignOrganization, foreignTeam, UUID.randomUUID(), "Foreign");
        seedAudit(UUID.randomUUID(), "UNREGISTERED_EVENT", "{}", "{}", BASE_TIME);

        var snapshot = operations.observe(organizationId);

        assertEquals(organizationId, snapshot.organizationId());
        assertEquals(5, snapshot.components().size());
        assertTrue(snapshot.recoveryCandidates().isEmpty());
        assertTrue(snapshot.components().stream().allMatch(component ->
                component.backlog() >= 0
                        && component.failures() >= 0));
    }

    @Test
    void keysetIndexesAreUsableByTheFrozenSqlShapes() {
        seedActivity(1, BASE_TIME, "OPS-INDEX");
        seedAudit(UUID.randomUUID(), "UNREGISTERED_EVENT", "{}", "{}", BASE_TIME);
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            String activityPlan = String.join("\n", jdbc.queryForList(
                    """
                    EXPLAIN SELECT activity_event_id
                    FROM crewscope.activity_event
                    WHERE organization_id = ? AND team_id = ? AND projection_name = ?
                      AND generation = ? AND (team_sequence, activity_event_id) > (?, ?)
                    ORDER BY team_sequence, activity_event_id LIMIT 201
                    """,
                    String.class,
                    organizationId.value(), teamId.value(),
                    ActivityEventProjector.PROJECTION_NAME.value(), 1L,
                    0L, new UUID(0L, 1L)));
            String auditPlan = String.join("\n", jdbc.queryForList(
                    """
                    EXPLAIN SELECT event_id
                    FROM crewscope.audit_event
                    WHERE organization_id = ? AND team_id = ?
                      AND (occurred_at, event_id) < (?, ?)
                    ORDER BY occurred_at DESC, event_id DESC LIMIT 201
                    """,
                    String.class,
                    organizationId.value(), teamId.value(),
                    BASE_TIME.plusSeconds(1).atOffset(ZoneOffset.UTC), UUID.randomUUID()));
            assertTrue(activityPlan.contains("Index"), activityPlan);
            assertTrue(auditPlan.contains("Index"), auditPlan);
        });
    }

    private void seedScope(UUID organization, UUID team, UUID principal, String suffix) {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organization, suffix + " Org");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', ?, 'ORGANIZATION', 'ACTIVE')
                """,
                principal, organization, suffix + " User");
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE')",
                team, organization, suffix + " Team");
    }

    private void bootstrapActivityProjection() {
        jdbc.update(
                """
                INSERT INTO crewscope.projection_definition (
                    projection_name, definition_version, projection_schema_version,
                    canonical_encoder, validator
                ) VALUES (?, 1, 1, 'activity.canonical-v1', 'activity.expected-v1')
                """,
                ActivityEventProjector.PROJECTION_NAME.value());
        inReplica(() -> {
            insertGeneration(organizationId.value(), 1, "ACTIVE", 1);
            jdbc.update(
                    """
                    INSERT INTO crewscope.projection_pointer (
                        organization_id, projection_name, active_generation, version, updated_at
                    ) VALUES (?, ?, 1, 0, ?)
                    """,
                    organizationId.value(), ActivityEventProjector.PROJECTION_NAME.value(),
                    BASE_TIME.atOffset(ZoneOffset.UTC));
        });
    }

    private UUID seedActivity(long sequence, Instant occurredAt, String itemKey) {
        return seedActivity(sequence, occurredAt, itemKey, UUID.randomUUID());
    }

    private UUID seedActivity(
            long sequence, Instant occurredAt, String itemKey, UUID correlationId) {
        UUID domainEventId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    subject_type, subject_id, aggregate_version, actor_type, actor_id,
                    correlation_id, occurred_at, payload
                ) VALUES (?, 'WORK_ITEM_CREATED', '1', ?, ?, 'WORK_ITEM', ?, ?,
                          'USER', ?, ?, ?, '{}'::JSONB)
                """,
                domainEventId, organizationId.value(), teamId.value(), subjectId, sequence - 1,
                principalId, correlationId, occurredAt.atOffset(ZoneOffset.UTC));
        ActivityEventId eventId = ActivityEventId.fromDomainEvent(domainEventId);
        jdbc.update(
                """
                INSERT INTO crewscope.activity_event (
                    organization_id, team_id, projection_name, generation,
                    activity_event_id, domain_event_id, projection_schema_version,
                    team_sequence, event_type, category, visibility, subject_type,
                    subject_id, actor_type, actor_principal_id, occurred_at,
                    payload, payload_schema_name, payload_schema_version
                ) VALUES (?, ?, ?, 1, ?, ?, 1, ?, 'WORK_ITEM_CREATED', 'WORK_ITEM',
                          'WORK_ITEM_PARTICIPANTS', 'WORK_ITEM', ?, 'USER', ?, ?,
                          CAST(? AS JSONB), 'activity.work-item-created', 1)
                """,
                organizationId.value(), teamId.value(), ActivityEventProjector.PROJECTION_NAME.value(),
                eventId.value(), domainEventId, sequence, subjectId, principalId,
                occurredAt.atOffset(ZoneOffset.UTC),
                "{\"itemKey\":\"" + itemKey + "\",\"title\":\"Title\",\"status\":\"OPEN\"}");
        jdbc.update(
                """
                INSERT INTO crewscope.activity_reference (
                    organization_id, projection_name, generation, activity_event_id,
                    reference_order, reference_type, reference_id
                ) VALUES (?, ?, 1, ?, 0, 'TEAM', ?),
                         (?, ?, 1, ?, 1, 'WORK_ITEM', ?)
                """,
                organizationId.value(), ActivityEventProjector.PROJECTION_NAME.value(),
                eventId.value(), teamId.value(), organizationId.value(),
                ActivityEventProjector.PROJECTION_NAME.value(), eventId.value(), subjectId);
        return domainEventId;
    }

    private void seedAudit(
            UUID eventId,
            String eventType,
            String authorization,
            String payload,
            Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, initiator_id, actor_type, actor_id,
                    event_type, subject_type, subject_id, outcome,
                    authorization_context, correlation_id, schema_version,
                    occurred_at, payload, event_category, retention_level
                ) VALUES (?, ?, ?, ?, 'USER', ?, ?, 'WORK_ITEM', ?, 'SUCCEEDED',
                          CAST(? AS JSONB), ?, '1', ?, CAST(? AS JSONB), 'SYSTEM', 'STANDARD')
                """,
                eventId, organizationId.value(), teamId.value(), principalId, principalId, eventType,
                UUID.randomUUID(), authorization, UUID.randomUUID(),
                occurredAt.atOffset(ZoneOffset.UTC), payload);
    }

    private void switchToEmptyGenerationTwo() {
        inReplica(() -> {
            insertGeneration(organizationId.value(), 2, "RETIRED", 2);
            jdbc.update(
                    "UPDATE crewscope.projection_generation SET status = 'RETIRED', version = 1 "
                            + "WHERE organization_id = ? AND projection_name = ? AND generation = 1",
                    organizationId.value(), ActivityEventProjector.PROJECTION_NAME.value());
            jdbc.update(
                    "UPDATE crewscope.projection_generation SET status = 'ACTIVE', version = 1 "
                            + "WHERE organization_id = ? AND projection_name = ? AND generation = 2",
                    organizationId.value(), ActivityEventProjector.PROJECTION_NAME.value());
            jdbc.update(
                    "UPDATE crewscope.projection_pointer SET active_generation = 2, version = 1 "
                            + "WHERE organization_id = ? AND projection_name = ?",
                    organizationId.value(), ActivityEventProjector.PROJECTION_NAME.value());
        });
    }

    private void insertGeneration(UUID organization, long generation, String status, long token) {
        jdbc.update(
                """
                INSERT INTO crewscope.projection_generation (
                    organization_id, projection_name, generation, definition_version,
                    status, fencing_token, version, created_at, updated_at
                ) VALUES (?, ?, ?, 1, ?, ?, 0, ?, ?)
                """,
                organization, ActivityEventProjector.PROJECTION_NAME.value(), generation,
                status, token, BASE_TIME.atOffset(ZoneOffset.UTC),
                BASE_TIME.atOffset(ZoneOffset.UTC));
    }

    private void inReplica(Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            operation.run();
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
