package io.crewscope.infrastructure.persistence.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.observability.sli.SliExecutionStatistics;
import io.crewscope.application.observability.sli.SliNotificationStatistics;
import io.crewscope.application.observability.sli.SliQueueingSnapshot;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedJdbcTestApplication;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The M12-I03 SLI facts against real PostgreSQL: exclusion denominators (user cancels,
 * policy denials, recipient-side notification failures), window arithmetic by
 * created_at, organization isolation, and the queueing snapshot shapes. The agent-run
 * leg asserts the zero baseline — its seed chain (runtime session, profile) is
 * prohibitively deep for a fixture and the query is the same shape as the execution leg.
 */
@SpringBootTest(
        classes = SharedJdbcTestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class JdbcSliStatisticsAdapterIT extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Instant NOW = Instant.now();
    private static final OffsetDateTime TEN_DAYS_AGO = atUtc(NOW.minusSeconds(10L * 24 * 3600));
    private static final OffsetDateTime FIVE_DAYS_AGO = atUtc(NOW.minusSeconds(5L * 24 * 3600));
    private static final OffsetDateTime TWO_DAYS_AGO = atUtc(NOW.minusSeconds(2L * 24 * 3600));
    private static final OffsetDateTime FORTY_DAYS_AGO = atUtc(NOW.minusSeconds(40L * 24 * 3600));
    private static final OffsetDateTime HUNDRED_SECONDS_AGO = atUtc(NOW.minusSeconds(100));
    private static final Instant ROLLING_START = NOW.minusSeconds(28L * 24 * 3600);
    private static final Instant RECENT_START = NOW.minusSeconds(3L * 24 * 3600);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final OrganizationId organizationA = OrganizationId.generate();
    private final OrganizationId organizationB = OrganizationId.generate();
    private final Map<OrganizationId, TaskScope> scopes = new HashMap<>();
    private final Map<UUID, Integer> executionAttempts = new HashMap<>();
    private final Map<UUID, UUID> firstExecutionIds = new HashMap<>();

    private JdbcSliStatisticsAdapter adapter;

    @BeforeEach
    void seedWorld() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        adapter = new JdbcSliStatisticsAdapter(jdbc);
        seedTaskWorld(organizationA);
        seedTaskWorld(organizationB);
        seedExecutions();
        seedNotifications();
        seedOutbox();
    }

    @Test
    void executionCountsExcludeCancelsAndPolicyDenialsAndRespectWindows() {
        SliExecutionStatistics rolling = adapter.executionStatistics(
                organizationA, ROLLING_START, NOW);
        assertEquals(6L, rolling.executionsTerminal());
        assertEquals(2L, rolling.executionsCompleted());
        assertEquals(3L, rolling.executionsFailed());
        assertEquals(1L, rolling.executionsCancelled());
        assertEquals(2L, rolling.executionsPolicyDenied());
        // Denominator: 6 terminal - 1 cancel - 2 policy denials = 3 reliability-relevant runs.
        assertEquals(3L, rolling.executionDenominator());
        assertEquals(0L, rolling.agentRunsTerminal());

        // The last three days see only the INTERNAL failure and the cancellation.
        SliExecutionStatistics recent = adapter.executionStatistics(
                organizationA, RECENT_START, NOW);
        assertEquals(2L, recent.executionsTerminal());
        assertEquals(0L, recent.executionsCompleted());
        assertEquals(1L, recent.executionsFailed());
        assertEquals(1L, recent.executionsCancelled());
        assertEquals(0L, recent.executionsPolicyDenied());
    }

    @Test
    void executionCountsStayInsideOneOrganization() {
        SliExecutionStatistics other = adapter.executionStatistics(
                organizationB, ROLLING_START, NOW);
        assertEquals(1L, other.executionsTerminal());
        assertEquals(1L, other.executionsCompleted());
    }

    @Test
    void notificationCountsSplitRecipientSideExclusionsFromPlatformFailures() {
        SliNotificationStatistics notifications = adapter.notificationStatistics(
                organizationA, ROLLING_START, NOW);
        assertEquals(2L, notifications.delivered());
        assertEquals(1L, notifications.failedFinal());
        assertEquals(2L, notifications.recipientSideExcluded());
        assertEquals(2L, notifications.invalidatedOrCancelled());
        assertEquals(3L, notifications.deliveryDenominator());
    }

    @Test
    void queueingSnapshotCountsThisOrganizationsBacklog() {
        SliQueueingSnapshot snapshot = adapter.queueingSnapshot(organizationA);
        assertEquals(1L, snapshot.outboxBacklog());
        // One entry seeded 100 seconds ago; the database clock matches the host closely.
        assertTrue(snapshot.outboxOldestAgeSeconds() >= 95,
                "oldest age " + snapshot.outboxOldestAgeSeconds());
        assertTrue(snapshot.outboxOldestAgeSeconds() <= 110,
                "oldest age " + snapshot.outboxOldestAgeSeconds());
        // No projection pointers exist for this organization: the lag reads zero.
        assertEquals(0L, snapshot.projectionMaxLagSeconds());
        // The worst delay is the oldest backlog age, not the entry count.
        assertEquals(snapshot.outboxOldestAgeSeconds(), snapshot.worstDelaySeconds());
    }

    @Test
    void invertedWindowsAreRejectedBeforeTouchingTheDatabase() {
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.executionStatistics(organizationA, NOW, ROLLING_START));
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.notificationStatistics(organizationA, NOW, NOW));
    }

    // ------------------------------------------------------------------ world seeding

    /** Minimal task FK chain (work item -> snapshot -> task) so task_execution rows fit. */
    private void seedTaskWorld(OrganizationId organizationId) {
        PrincipalId principalId = PrincipalId.generate();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'SLI Seed', 'ORGANIZATION', 'ACTIVE')
                """,
                principalId.value(), organizationId.value());
        UUID teamId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, 'SLI Team', 'ACTIVE')",
                teamId, organizationId.value());
        UUID workspaceId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'SLI Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId);
        UUID projectId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'SLI', 'SLI Project', ?, ?)
                """,
                projectId, organizationId.value(), teamId, workspaceId,
                principalId.value(), principalId.value());
        UUID workItemId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority,
                    created_at, updated_at, created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'SLI work item', 'IN_PROGRESS', 'HIGH',
                          ?, ?, ?, ?)
                """,
                workItemId, organizationId.value(), teamId, workspaceId, projectId,
                "SLI-" + organizationId.value().toString().substring(0, 12),
                TEN_DAYS_AGO, TEN_DAYS_AGO,
                principalId.value(), principalId.value());
        UUID snapshotId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshotId, organizationId.value(), teamId, workspaceId, projectId, workItemId,
                "a".repeat(64), TEN_DAYS_AGO, TEN_DAYS_AGO, principalId.value(),
                TEN_DAYS_AGO, principalId.value());
        UUID taskId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    status, objective, acceptance_criteria, created_at,
                    created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'SLI objective',
                          '["none"]'::JSONB, ?, ?, ?, ?)
                """,
                taskId, organizationId.value(), teamId, workspaceId, projectId, workItemId,
                snapshotId, TEN_DAYS_AGO, principalId.value(), TEN_DAYS_AGO,
                principalId.value());
        scopes.put(organizationId, new TaskScope(
                organizationId.value(), teamId, workspaceId, projectId, taskId,
                principalId.value()));
    }

    private record TaskScope(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID taskId,
            UUID principalId) {}

    private void seedExecutions() {
        TaskScope scope = scopes.get(organizationA);
        insertExecution(scope, "COMPLETED", null, TEN_DAYS_AGO);
        insertExecution(scope, "COMPLETED", null, FIVE_DAYS_AGO);
        insertExecution(scope, "FAILED", "POLICY_VIOLATION", FIVE_DAYS_AGO);
        insertExecution(scope, "FAILED", "AUTHORIZATION", FIVE_DAYS_AGO);
        insertExecution(scope, "FAILED", "INTERNAL", TWO_DAYS_AGO);
        insertExecution(scope, "CANCELLED", null, TWO_DAYS_AGO);
        // In flight and out of window: neither belongs in any terminal count.
        insertExecution(scope, "RUNNING", null, TWO_DAYS_AGO);
        insertExecution(scope, "COMPLETED", null, FORTY_DAYS_AGO);
        insertExecution(scopes.get(organizationB), "COMPLETED", null, FIVE_DAYS_AGO);
    }

    private void insertExecution(
            TaskScope scope, String status, String failureClass, OffsetDateTime createdAt) {
        boolean terminal = "COMPLETED".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
        UUID executionId = UUID.randomUUID();
        int attempt = executionAttempts.merge(scope.organizationId(), 1, Integer::sum);
        // attempt 1 roots the parent chain; later attempts point back at it (the parent
        // shape check demands a parent for every attempt > 1).
        if (attempt == 1) {
            firstExecutionIds.put(scope.organizationId(), executionId);
        }
        // The control shape demands a cancel request trail for CANCELLED; the fencing
        // shape demands a token for RUNNING.
        jdbc.update(
                """
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, parent_execution_id, priority, not_before, status,
                    control_request_type, control_requested_by_principal_id,
                    control_requested_at, control_request_reason,
                    terminal_decided_by_principal_id, terminal_decided_at,
                    terminal_failure_class, terminal_failure_code, last_fencing_token,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 100, ?, 50, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?)
                """,
                executionId, scope.organizationId(), scope.teamId(), scope.workspaceId(),
                scope.projectId(), scope.taskId(), attempt,
                // attempt 1 roots the chain: a NULL parent is exactly what the shape
                // check demands (and forbids a self-reference).
                attempt > 1 ? firstExecutionIds.get(scope.organizationId()) : null,
                createdAt, status,
                "CANCELLED".equals(status) ? "CANCEL" : null,
                "CANCELLED".equals(status) ? scope.principalId() : null,
                "CANCELLED".equals(status) ? createdAt : null,
                "CANCELLED".equals(status) ? "SLI seed cancellation" : null,
                terminal ? scope.principalId() : null,
                terminal ? createdAt : null,
                failureClass,
                failureClass == null ? null : "SLI_SEED_" + failureClass,
                "RUNNING".equals(status) ? 1L : null,
                createdAt, scope.principalId(), createdAt, scope.principalId());
    }

    /** Organization A's delivery matrix: every exclusion class plus window and isolation. */
    private void seedNotifications() {
        insertDelivery(organizationA, "SUCCEEDED", null, "ACCEPTED", FIVE_DAYS_AGO);
        insertDelivery(organizationA, "SUCCEEDED", null, "ACCEPTED", TWO_DAYS_AGO);
        insertDelivery(organizationA, "FAILED_FINAL", "PROVIDER_REJECTED",
                "FAILED_FINAL", FIVE_DAYS_AGO);
        insertDelivery(organizationA, "FAILED_FINAL", "RECIPIENT_UNAVAILABLE",
                "FAILED_FINAL", FIVE_DAYS_AGO);
        insertDelivery(organizationA, "FAILED_FINAL", "AUTHORIZATION_REVOKED",
                "FAILED_FINAL", TWO_DAYS_AGO);
        insertDelivery(organizationA, "INVALIDATED", null, "INVALIDATED", FIVE_DAYS_AGO);
        insertDelivery(organizationA, "CANCELLED", null, "CANCELLED", FIVE_DAYS_AGO);
        // Out of window and foreign organization: excluded from every organization-A count.
        insertDelivery(organizationA, "SUCCEEDED", null, "ACCEPTED", FORTY_DAYS_AGO);
        insertDelivery(organizationB, "SUCCEEDED", null, "ACCEPTED", FIVE_DAYS_AGO);
    }

    /** The provider graph behind a delivery is fixture-hostile; FK triggers stay off. */
    private void insertDelivery(
            OrganizationId organizationId,
            String deliveryStatus,
            String failureCode,
            String receiptResult,
            OffsetDateTime createdAt) {
        UUID actionId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String actionDigest = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String deduplicationKey = UUID.randomUUID().toString().replace("-", "").repeat(2);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_planned_action (
                        organization_id, team_id, recipient_member_id,
                        projection_name, generation, action_id, intent_id,
                        source_identity_hash, template_id, template_version, variable_hash,
                        recipient_mapping_id, recipient_mapping_version,
                        provider_binding_id, provider_binding_version,
                        connection_id, connection_version,
                        connection_grant_id, connection_grant_version,
                        team_policy_id, team_policy_version, preference_version,
                        deduplication_key, authorization_digest, not_before, valid_until,
                        status, action_digest, version, created_at, updated_at
                    ) VALUES (
                        ?, ?, ?, 'member-inbox', 1, ?, ?, ?, ?, 1, ?, ?, 0, ?, 0,
                        ?, 0, ?, 0, ?, 0, 0, ?, ?, ?, ?, 'PLANNED', ?, 0, ?, ?
                    )
                    """,
                    organizationId.value(), UUID.randomUUID(), UUID.randomUUID(),
                    actionId, UUID.randomUUID(), "d".repeat(64), UUID.randomUUID(),
                    "e".repeat(64), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), deduplicationKey, "f".repeat(64),
                    createdAt, createdAt.plusSeconds(3_600), actionDigest, createdAt, createdAt);
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_receipt (
                        organization_id, receipt_id, delivery_id, action_id, action_digest,
                        deduplication_key, result, failure_code, evidence_code,
                        provider_receipt_hash, provider_message_hash, received_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SLI_SEED_EVIDENCE', ?, ?, ?)
                    """,
                    organizationId.value(), receiptId, deliveryId, actionId, actionDigest,
                    deduplicationKey, receiptResult, failureCode,
                    // The result shape check: only ACCEPTED carries provider hashes.
                    "ACCEPTED".equals(receiptResult) ? "a".repeat(64) : null,
                    "ACCEPTED".equals(receiptResult) ? "b".repeat(64) : null,
                    createdAt);
            jdbc.update(
                    """
                    INSERT INTO crewscope.notification_delivery (
                        organization_id, delivery_id, action_id, action_digest,
                        deduplication_key, status, attempt_count, receipt_id,
                        invalidation_reason, version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 0, ?, ?)
                    """,
                    organizationId.value(), deliveryId, actionId, actionDigest,
                    deduplicationKey, deliveryStatus, receiptId,
                    // The shape check ties INVALIDATED to a non-null reason.
                    "INVALIDATED".equals(deliveryStatus) ? "SOURCE" : null,
                    createdAt, createdAt);
            jdbc.execute("SET LOCAL session_replication_role = origin");
        });
    }

    private void seedOutbox() {
        insertOutboxRow(organizationA, HUNDRED_SECONDS_AGO);
        insertOutboxRow(organizationB, HUNDRED_SECONDS_AGO);
    }

    private void insertOutboxRow(OrganizationId organizationId, OffsetDateTime createdAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version, organization_id,
                    subject_type, subject_id, aggregate_version, actor_type, actor_id,
                    correlation_id, occurred_at, payload
                ) VALUES (?, 'SLI_SEED_EVENT', '1', ?, 'WORK_ITEM', ?, 0, 'USER', ?, ?, ?,
                          '{}'::JSONB)
                """,
                eventId, organizationId.value(), UUID.randomUUID(),
                // The event actor must point at a seeded principal (FK).
                scopes.get(organizationId).principalId(), UUID.randomUUID(),
                createdAt);
        jdbc.update(
                """
                INSERT INTO crewscope.outbox_event (
                    id, domain_event_id, topic, partition_key, delivery_status, created_at
                ) VALUES (?, ?, 'crewscope.sli.seed', ?, 'PENDING', ?)
                """,
                UUID.randomUUID(), eventId, organizationId.value(), createdAt);
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
