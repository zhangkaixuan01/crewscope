package io.crewscope.infrastructure.event.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.inbox.CrewScopeInboxEventTypes;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL contract for the budget alert inbox lane (M10-F03): one crossing opens
 * exactly one OPEN EXCEPTION×BUDGET item per currently eligible team OWNER or ADMIN,
 * a member holding both roles still gets one item, revoked or expired grants and
 * unroled members get none, and a team without any eligible recipient dead-letters
 * nothing — the receipt is still written so the scan never re-delivers.
 */
@SpringBootTest(
        classes = InboxBudgetAlertProjectionIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class InboxBudgetAlertProjectionIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-10-05T02:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private OrganizationId organizationId;
    private UUID teamId;
    private UUID workspaceId;
    private UUID grantorPrincipalId;
    private GenerationAwareProjectionRunner runner;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "DELETE FROM crewscope.projection_definition WHERE projection_name = ?",
                InboxEventProjector.PROJECTION_NAME.value());
        seedScope();
        InboxEventProjector projector = new InboxEventProjector(
                jdbc,
                objectMapper,
                CrewScopeInboxEventTypes.reviewedRegistry(),
                mock(NotificationIntentProjector.class));
        JdbcProjectionGenerationRegistry registry =
                new JdbcProjectionGenerationRegistry(jdbc, transactionManager);
        JdbcGenerationProjectionStore store = new JdbcGenerationProjectionStore(jdbc);
        ProjectionEventJsonMapper mapper = new ProjectionEventJsonMapper(objectMapper);
        runner = new GenerationAwareProjectionRunner(
                projector, registry, store, mapper, transactionManager,
                Clock.fixed(BASE_TIME.plusSeconds(300), ZoneOffset.UTC));
    }

    @Test
    void opensOneOpenExceptionPerEligibleOwnerOrAdminAndReplayAddsNothing() {
        UUID roleHolder = seedMember("Role Holder");
        grant(roleHolder, "TEAM_OWNER", "ACTIVE", null);
        grant(roleHolder, "TEAM_ADMIN", "ACTIVE", null);
        UUID admin = seedMember("Admin Only");
        grant(admin, "TEAM_ADMIN", "ACTIVE", null);
        // None of these three may hear about the crossing.
        UUID revoked = seedMember("Revoked Admin");
        grant(revoked, "TEAM_ADMIN", "REVOKED", null);
        UUID expired = seedMember("Expired Owner");
        grant(expired, "TEAM_OWNER", "ACTIVE", BASE_TIME.plusSeconds(1800));
        UUID plain = seedMember("Plain Member");
        UUID eventId = seedBudgetAlertEvent();

        runner.consume(publication(eventId));
        runner.consume(publication(eventId));

        // Replay converges: the two eligible recipients keep exactly one item each.
        assertEquals(2, budgetItemCount());
        assertEquals(1, itemsFor(roleHolder));
        assertEquals(1, itemsFor(admin));
        assertEquals(0, itemsFor(revoked) + itemsFor(expired) + itemsFor(plain));
        assertEquals(2, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM crewscope.inbox_item
                        WHERE item_type = 'EXCEPTION' AND source_type = 'BUDGET'
                          AND source_status = 'OPEN' AND priority = 'HIGH'
                          AND source_revision = 0
                        """,
                Integer.class));
        assertEquals(1, receiptCount());
    }

    @Test
    void aTeamWithoutEligibleRecipientsRecordsTheReceiptAndProjectsNothing() {
        seedMember("Lonely Member");
        UUID eventId = seedBudgetAlertEvent();

        runner.consume(publication(eventId));

        assertEquals(0, budgetItemCount());
        assertEquals(1, receiptCount());
    }

    // ------------------------------------------------------------------ seeds and helpers

    private void seedScope() {
        organizationId = OrganizationId.generate();
        teamId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        grantorPrincipalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) "
                        + "VALUES (?, 'Budget Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) "
                        + "VALUES (?, ?, 'Budget Team', 'ACTIVE')",
                teamId, organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'Budget Grantor', 'ORGANIZATION', 'ACTIVE')
                """,
                grantorPrincipalId, organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Budget Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId);
        seedRole("TEAM_OWNER");
        seedRole("TEAM_ADMIN");
    }

    private void seedRole(String roleKey) {
        jdbc.update(
                """
                INSERT INTO crewscope.team_role (
                    id, organization_id, team_id, role_key, name, built_in,
                    permissions, scope_type, status
                ) VALUES (?, ?, ?, ?, ?, TRUE, '[]'::JSONB, 'TEAM', 'ACTIVE')
                """,
                UUID.randomUUID(), organizationId.value(), teamId, roleKey,
                roleKey + " name");
    }

    private UUID seedMember(String label) {
        UUID principalId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', ?, 'ORGANIZATION', 'ACTIVE')
                """,
                principalId, organizationId.value(), label);
        jdbc.update(
                """
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?, ?, ?)
                """,
                memberId, organizationId.value(), teamId, principalId,
                BASE_TIME.atOffset(ZoneOffset.UTC), BASE_TIME.atOffset(ZoneOffset.UTC),
                BASE_TIME.atOffset(ZoneOffset.UTC));
        return memberId;
    }

    private void grant(UUID memberId, String roleKey, String status, Instant expiresAt) {
        UUID roleId = jdbc.queryForObject(
                "SELECT id FROM crewscope.team_role WHERE team_id = ? AND role_key = ?",
                UUID.class, teamId, roleKey);
        jdbc.update(
                """
                INSERT INTO crewscope.team_member_role (
                    id, organization_id, team_id, team_member_id, team_role_id,
                    scope_type, granted_by_principal_id, valid_from, expires_at,
                    revoked_at, status
                ) VALUES (?, ?, ?, ?, ?, 'TEAM', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId.value(), teamId, memberId, roleId,
                grantorPrincipalId, BASE_TIME.atOffset(ZoneOffset.UTC),
                expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC),
                "REVOKED".equals(status) ? BASE_TIME.atOffset(ZoneOffset.UTC) : null,
                status);
    }

    private UUID seedBudgetAlertEvent() {
        UUID alertId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"alertId":"%s","teamId":"%s","usageMonth":"2026-10",
                 "kind":"TOKEN","level":"EXCEEDED","metricValue":1200,"threshold":1000,
                 "currencyCode":null,"detectedAt":"2026-10-05T02:00:00Z"}
                """.formatted(alertId, teamId);
        jdbc.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id, team_id,
                    workspace_id, subject_type, subject_id, aggregate_version,
                    actor_type, actor_id, correlation_id, occurred_at, payload
                ) VALUES (?, 'TEAM_BUDGET_ALERT_RECORDED', '1', ?, ?, NULL,
                          'TEAM_BUDGET_ALERT', ?, 0,
                          'SERVICE', NULL, ?, ?, CAST(? AS JSONB))
                """,
                eventId, organizationId.value(), teamId, alertId, alertId,
                BASE_TIME.atOffset(ZoneOffset.UTC), payload);
        jdbc.update(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key, delivery_status,
                    retry_count, created_at, version, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)
                """,
                UUID.randomUUID(), eventId, PendingOutboxEvent.DOMAIN_EVENTS_TOPIC,
                organizationId + ":TEAM_BUDGET_ALERT:" + alertId,
                BASE_TIME.atOffset(ZoneOffset.UTC), BASE_TIME.atOffset(ZoneOffset.UTC));
        return eventId;
    }

    private EventPublication publication(UUID eventId) {
        return new JdbcProjectionEventHistoryStore(jdbc, objectMapper)
                .read(organizationId, java.util.Optional.empty(), 100)
                .events().stream()
                .map(ProjectionHistoryEvent::publication)
                .filter(event -> event.eventId().equals(eventId))
                .findFirst()
                .orElseThrow();
    }

    private int budgetItemCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.inbox_item WHERE source_type = 'BUDGET'",
                Integer.class);
    }

    private int itemsFor(UUID memberId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.inbox_item WHERE member_id = ?",
                Integer.class, memberId);
    }

    private int receiptCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_consumer_receipt
                WHERE organization_id = ? AND projection_name = ?
                """,
                Integer.class, organizationId.value(),
                InboxEventProjector.PROJECTION_NAME.value());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
