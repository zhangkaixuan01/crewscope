package io.crewscope.infrastructure.persistence.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.crewscope.application.observability.ModelUsageMonthDetailRow;
import io.crewscope.application.observability.ModelUsageMonthRoleCurrencyRow;
import io.crewscope.application.observability.TaskQualityStatistics;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL contract for the F03 read side (M10-F03): month pages keep the index order
 * with an exclusive cursor and stay scoped to one team, month aggregates fold per
 * role×currency grain with 'XXX' rows carrying NULL costs, the detail query exposes the
 * grain rows in a deterministic order with the unpriced price triple last, and the
 * quality adapter counts terminal execution statuses inside the half-open window while
 * the first-pass rate takes each task's earliest non-invalidated review request.
 */
class TeamObservabilityQueryIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    /** 2026-10 in Asia/Shanghai — the reporting zone shipped in application.yml. */
    private static final Instant WINDOW_START = Instant.parse("2026-09-30T16:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-31T16:00:00Z");
    private static final Instant WITHIN = Instant.parse("2026-10-05T12:00:00Z");
    private static final OffsetDateTime FIRST_FACT =
            Instant.parse("2026-10-05T08:00:00Z").atOffset(ZoneOffset.UTC);
    private static final OffsetDateTime LAST_FACT =
            Instant.parse("2026-10-05T12:00:00Z").atOffset(ZoneOffset.UTC);
    private static final String HASH = "cd".repeat(32);

    private static JdbcTemplate jdbc;
    private static TransactionTemplate inTransaction;
    private static JdbcModelUsageRollupQueryAdapter rollups;
    private static JdbcTaskQualityStatisticsAdapter quality;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final Map<UUID, UUID> contextPackages = new HashMap<>();
    private final Map<UUID, UUID> lastReviewRequests = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong itemSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private UUID workspaceId;
    private UUID projectId;
    private UUID principalId;
    private UUID memberId;

    @BeforeAll
    static void migrateSchemaAndAssembleAdapters() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true)
                // The chain tip this test rides (V65 only rebuilds the inbox CHECK).
                .target(MigrationVersion.fromVersion("65"))
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        inTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        rollups = new JdbcModelUsageRollupQueryAdapter(jdbc);
        quality = new JdbcTaskQualityStatisticsAdapter(jdbc);
    }

    @BeforeEach
    void resetAndSeedScope() {
        jdbc.update("""
                TRUNCATE TABLE crewscope.model_usage_monthly_rollup,
                    crewscope.team_budget_alert, crewscope.task_execution,
                    crewscope.task, crewscope.task_responsibility_snapshot,
                    crewscope.review_request_projection, crewscope.review_request,
                    crewscope.review_context_package, crewscope.responsibility_assignment,
                    crewscope.work_item, crewscope.work_project, crewscope.workspace,
                    crewscope.team_member, crewscope.principal, crewscope.team,
                    crewscope.organization CASCADE
                """);
        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        principalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(), "F03 Query Organization");
        jdbc.update("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'F03 Query Team', 'ACTIVE')
                """, teamId.value(), organizationId.value());
        jdbc.update("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, visibility, status
                ) VALUES (?, ?, 'USER', 'F03 Query Actor', 'ORGANIZATION', 'ACTIVE')
                """, principalId, organizationId.value());
        jdbc.update("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'F03 Query Workspace', 'ACTIVE')
                """, workspaceId, organizationId.value(), teamId.value());
        jdbc.update("""
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'F03', 'F03 Query Project', ?, ?)
                """, projectId, organizationId.value(), teamId.value(), workspaceId,
                principalId, principalId);
        memberId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.team_member "
                        + "(id, organization_id, team_id, user_principal_id, status, "
                        + "join_method, joined_at) VALUES (?, ?, ?, ?, 'ACTIVE', 'IMPORT', ?)",
                memberId, organizationId.value(), teamId.value(), principalId,
                OffsetDateTime.ofInstant(WITHIN, ZoneOffset.UTC));
    }

    @Test
    void monthPagesFollowTheIndexOrderWithAnExclusiveCursorAndTeamScope() {
        seedPricedRow("2026-10", "CHAT_PRIMARY", "USD", 1, 1, 100, 40, 0,
                "0.044", "0.0528", null, 1);
        seedPricedRow("2026-09", "EMBEDDING", "CNY", 1, 1, 500, 0, 0, "0.25", null, null, 1);
        seedUnpricedRow("2026-09", "DISTILLATION", 1, 30, 1, 0);
        seedPricedRow("2026-08", "CHAT_FALLBACK", "USD", 1, 1, 10, 4, 0,
                "0.0044", null, null, 1);
        seedPricedRow("2026-07", "COMPACTION", "USD", 1, 1, 5, 1, 0,
                "0.0022", null, null, 1);
        // Another team's months must never leak into this team's pages.
        seedPricedRow(TeamId.generate(), "2026-11", "CHAT_PRIMARY", "USD", 1, 1, 999, 0, 0,
                "0.4396", null, null, 1);

        assertEquals(List.of("2026-10", "2026-09", "2026-08"),
                rollups.findUsageMonths(organizationId, teamId, null, 3));
        assertEquals(List.of("2026-07"),
                rollups.findUsageMonths(organizationId, teamId, "2026-08", 3));
        assertEquals(List.of("2026-10", "2026-09", "2026-08", "2026-07"),
                rollups.findUsageMonths(organizationId, teamId, null, 12));
    }

    @Test
    void monthAggregatesFoldPerRoleCurrencyGrainWithNullCostsOnSentinelRows() {
        seedPricedRow("2026-10", "CHAT_PRIMARY", "USD", 1, 1, 1_000_000, 500_000, 300_000,
                "0.44", "0.66", "0.0042", 1);
        // Same grain except attempt — the aggregate folds attempts into one row.
        seedPricedRow("2026-10", "CHAT_PRIMARY", "USD", 1, 2, 10_000, 5_000, 0,
                "0.0044", "0.0066", null, 1);
        seedPricedRow("2026-10", "EMBEDDING", "CNY", 1, 1, 2_000_000, 0, 0,
                "1", null, null, 1);
        seedUnpricedRow("2026-10", "DISTILLATION", 1, 30, 1, 0);
        seedUnpricedRow("2026-10", "EMBEDDING", 2, 0, 1, 1);
        seedPricedRow("2026-09", "EMBEDDING", "CNY", 1, 1, 700, 0, 0,
                "0.00035", null, null, 1);

        List<ModelUsageMonthRoleCurrencyRow> rows = rollups.findMonthAggregates(
                organizationId, teamId, List.of("2026-10"));

        assertEquals(4, rows.size());
        ModelUsageMonthRoleCurrencyRow chat = rows.stream()
                .filter(row -> "CHAT_PRIMARY".equals(row.role())).findFirst().orElseThrow();
        assertEquals("USD", chat.currencyCode());
        assertEquals(1_010_000L, chat.inputTokens());
        assertEquals(505_000L, chat.outputTokens());
        assertEquals(300_000L, chat.cachedInputTokens());
        assertEquals(0, new BigDecimal("0.4444").compareTo(chat.inputCost()));
        assertEquals(0, new BigDecimal("0.6666").compareTo(chat.outputCost()));
        assertEquals(0, new BigDecimal("0.0042").compareTo(chat.cachedInputCost()));
        assertEquals(2L, chat.factCount());
        assertEquals(0L, chat.unreportedFactCount());
        ModelUsageMonthRoleCurrencyRow unpriced = rows.stream()
                .filter(row -> "XXX".equals(row.currencyCode())).findFirst().orElseThrow();
        assertEquals("DISTILLATION", unpriced.role());
        assertNull(unpriced.inputCost());
        assertEquals(30L, unpriced.inputTokens());
        ModelUsageMonthRoleCurrencyRow embedding = rows.stream()
                .filter(row -> "EMBEDDING".equals(row.role())).findFirst().orElseThrow();
        // The priced CNY grain and the unpriced 'XXX' grain stay separate rows.
        assertEquals("CNY", embedding.currencyCode());
        assertEquals(2_000_000L, embedding.inputTokens());
        assertEquals(0, new BigDecimal("1").compareTo(embedding.inputCost()));
    }

    @Test
    void monthDetailKeepsGrainRowsOrderedWithUnpricedPriceTriplesLast() {
        seedPricedRow("2026-10", "CHAT_PRIMARY", "USD", 3, 1, 1_000_000, 500_000, 0,
                "0.44", "0.66", null, 1);
        seedPricedRow("2026-10", "CHAT_PRIMARY", "USD", 1, 1, 2_000_000, 0, 0,
                "0.88", null, null, 1);
        seedUnpricedRow("2026-10", "EMBEDDING", 2, 7_000, 1, 1);

        List<ModelUsageMonthDetailRow> rows = rollups.findMonthDetail(
                organizationId, teamId, "2026-10");

        assertEquals(3, rows.size());
        assertEquals(1L, rows.get(0).priceRevision(), "lowest price revision first");
        assertEquals(2_000_000L, rows.get(0).inputTokens());
        assertEquals(3L, rows.get(1).priceRevision());
        ModelUsageMonthDetailRow unpriced = rows.get(2);
        assertEquals("EMBEDDING", unpriced.role());
        assertEquals("XXX", unpriced.currencyCode());
        assertNull(unpriced.catalogRevision());
        assertNull(unpriced.priceRevision());
        assertNull(unpriced.inputCost());
        assertEquals(2, unpriced.attempt());
        assertEquals(1L, unpriced.factCount());
        assertEquals(1L, unpriced.unreportedFactCount());
    }

    @Test
    void executionsCountTerminalStatusesInsideTheHalfOpenWindow() {
        seedExecution("COMPLETED", WITHIN);
        seedExecution("FAILED", WITHIN.plusSeconds(3_600));
        seedExecution("CANCELLED", WITHIN.plusSeconds(7_200));
        // Exactly on either boundary stays outside: the window is [start, end).
        seedExecution("COMPLETED", WINDOW_END);
        seedExecution("COMPLETED", WINDOW_START.minusSeconds(1));

        TaskQualityStatistics statistics = quality.findForWindow(
                organizationId, teamId, WINDOW_START, WINDOW_END);

        assertEquals(3L, statistics.executionAttempts());
        assertEquals(1L, statistics.completedAttempts());
        assertEquals(1L, statistics.failedAttempts());
        assertEquals(1L, statistics.cancelledAttempts());
        assertEquals(0L, statistics.enteredReview());
        assertEquals(0L, statistics.firstPassApproved());
    }

    @Test
    void firstPassTakesTheEarliestNonInvalidatedRequestPerTask() {
        // Earliest request INVALIDATED: the next revision speaks, and it passed with no
        // modification rounds — this task counts as a first pass.
        UUID taskA = seedExecution("COMPLETED", WITHIN);
        seedReviewRequest(taskA, 1, "INVALIDATED", null, 0, WITHIN, false);
        seedReviewRequest(taskA, 2, "OPEN", "APPROVED", 0, WITHIN.plusSeconds(60), true);
        // Earliest request is the only one that counts: a later clean revision cannot
        // rescue a task whose first review took a modification round.
        UUID taskB = seedExecution("COMPLETED", WITHIN.plusSeconds(120));
        seedReviewRequest(taskB, 1, "OPEN", "APPROVED", 1, WITHIN.plusSeconds(180), true);
        seedReviewRequest(taskB, 2, "OPEN", "APPROVED", 0, WITHIN.plusSeconds(240), true);
        // Rejected first request: entered review, not a first pass.
        UUID taskC = seedExecution("COMPLETED", WITHIN.plusSeconds(300));
        seedReviewRequest(taskC, 1, "OPEN", "REJECTED", 0, WITHIN.plusSeconds(360), true);
        // A clean first pass outside the window never enters this month's denominator.
        UUID taskD = seedExecution("COMPLETED", WITHIN.plusSeconds(420));
        seedReviewRequest(taskD, 1, "OPEN", "APPROVED", 0,
                WINDOW_END.plusSeconds(3_600), true);

        TaskQualityStatistics statistics = quality.findForWindow(
                organizationId, teamId, WINDOW_START, WINDOW_END);

        assertEquals(4L, statistics.executionAttempts());
        assertEquals(3L, statistics.enteredReview());
        assertEquals(1L, statistics.firstPassApproved());
    }

    // ------------------------------------------------------------------ rollup seeds

    /** Priced grain row: the price triple is pinned and costs satisfy ck_rollup_priced_shape. */
    private void seedPricedRow(
            String month, String role, String currency, long priceRevision, int attempt,
            long inputTokens, long outputTokens, long cachedTokens,
            String inputCost, String outputCost, String cachedCost, long factCount) {
        seedPricedRow(teamId, month, role, currency, priceRevision, attempt,
                inputTokens, outputTokens, cachedTokens,
                inputCost, outputCost, cachedCost, factCount);
    }

    private void seedPricedRow(
            TeamId team, String month, String role, String currency, long priceRevision,
            int attempt, long inputTokens, long outputTokens, long cachedTokens,
            String inputCost, String outputCost, String cachedCost, long factCount) {
        insertRollupRow(team, month, role, "deepseek", "deepseek-flash", currency,
                UUID.randomUUID(), 3L, priceRevision, attempt,
                inputTokens, outputTokens, cachedTokens,
                new BigDecimal(inputCost),
                outputCost == null ? null : new BigDecimal(outputCost),
                cachedCost == null ? null : new BigDecimal(cachedCost),
                factCount, 0L);
    }

    /** Unpriced grain row: 'XXX' sentinel, NULL price triple and costs, tokens stay counted. */
    private void seedUnpricedRow(
            String month, String role, int attempt, long tokens, long factCount,
            long unreportedFactCount) {
        insertRollupRow(teamId, month, role, "dashscope", "text-embedding-v4", "XXX",
                null, null, null, attempt,
                tokens, 0L, 0L, null, null, null, factCount, unreportedFactCount);
    }

    private void insertRollupRow(
            TeamId team, String month, String role, String provider, String model,
            String currency, UUID catalogEntryId, Long catalogRevision, Long priceRevision,
            int attempt, long inputTokens, long outputTokens, long cachedTokens,
            BigDecimal inputCost, BigDecimal outputCost, BigDecimal cachedCost,
            long factCount, long unreportedFactCount) {
        jdbc.update("""
                INSERT INTO crewscope.model_usage_monthly_rollup (
                    organization_id, team_id, usage_month, role, provider_key, model_id,
                    currency_code, catalog_entry_id, catalog_revision, price_revision,
                    attempt, input_tokens, output_tokens, cached_input_tokens,
                    input_cost, output_cost, cached_input_cost,
                    fact_count, unreported_fact_count, first_fact_at, last_fact_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationId.value(), team.value(), month, role, provider, model,
                currency, catalogEntryId, catalogRevision, priceRevision,
                attempt, inputTokens, outputTokens, cachedTokens,
                inputCost, outputCost, cachedCost, factCount, unreportedFactCount,
                FIRST_FACT, LAST_FACT);
    }

    // ------------------------------------------------------------------ quality seeds

    /** One task chain (work item → snapshot → task) with one execution in the given state. */
    private UUID seedExecution(String status, Instant createdAt) {
        OffsetDateTime at = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        UUID workItemId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority, due_at,
                    source_provider, created_at, updated_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'BACKLOG', 'HIGH', NULL,
                          'CREWSCOPE', ?, ?, ?, ?)
                """,
                workItemId, organizationId.value(), teamId.value(), workspaceId, projectId,
                "F03-" + itemSequence.incrementAndGet(), "F03 execution item", at, at,
                principalId, principalId);
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshotId, organizationId.value(), teamId.value(), workspaceId, projectId,
                workItemId, HASH, at, at, principalId, at, principalId);
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    status, objective, acceptance_criteria,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'F03 objective',
                          '["Accept"]'::JSONB, ?, ?, ?, ?)
                """,
                taskId, organizationId.value(), teamId.value(), workspaceId, projectId,
                workItemId, snapshotId, at, principalId, at, principalId);
        boolean terminal = status.equals("COMPLETED") || status.equals("FAILED")
                || status.equals("CANCELLED");
        boolean cancelled = status.equals("CANCELLED");
        jdbc.update("""
                INSERT INTO crewscope.task_execution (
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    attempt, max_attempts, priority, not_before, status,
                    waiting_reason, waiting_since,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id,
                    terminal_decided_by_principal_id, terminal_decided_at,
                    terminal_failure_class, terminal_failure_code,
                    control_request_type, control_requested_by_principal_id,
                    control_requested_at, control_request_reason
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 3, 80, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId.value(), teamId.value(), workspaceId,
                projectId, taskId, at, status, at, principalId, at, principalId,
                terminal ? principalId : null,
                terminal ? at : null,
                "FAILED".equals(status) ? "INTERNAL" : null,
                "FAILED".equals(status) ? "SEED_FAILURE" : null,
                cancelled ? "CANCEL" : null,
                cancelled ? principalId : null,
                cancelled ? at : null,
                cancelled ? "seed cancel" : null);
        return taskId;
    }

    /**
     * Suspends trigger and foreign-key enforcement the way the production projector's
     * own seed does — the review tables guard a write path this seed does not reproduce
     * (e.g. the context package's subject reference).
     */
    private void withoutGuards(Runnable work) {
        inTransaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            work.run();
        });
    }

    /**
     * One review request plus its projection row. The seed suspends trigger and
     * foreign-key enforcement for the review tables the way the production projector's
     * own test does — they guard a write path this seed does not reproduce.
     */
    private void seedReviewRequest(
            UUID taskId, int revision, String requestStatus, String latestDecisionType,
            int modificationRound, Instant requestCreatedAt, boolean withProjection) {
        OffsetDateTime at = OffsetDateTime.ofInstant(requestCreatedAt, ZoneOffset.UTC);
        UUID executionId = jdbc.queryForObject(
                "SELECT id FROM crewscope.task_execution WHERE task_id = ?", UUID.class, taskId);
        UUID contextId = contextPackages.computeIfAbsent(executionId, key -> {
            UUID packageId = UUID.randomUUID();
            withoutGuards(() -> jdbc.update("""
                    INSERT INTO crewscope.review_context_package (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, package_version,
                        subject_id, subject_type, subject_hash, diff_artifact_id,
                        diff_final_hash, coding_target_snapshot_id, coding_target_revision,
                        coding_target_hash, diff_generation, diff_manifest_hash,
                        test_evidence_id, test_evidence_hash, reviewer_agent_profile_id,
                        reviewer_agent_profile_version, reviewer_agent_principal_id,
                        reviewer_owner_member_id, subject_owner_member_id,
                        reviewer_relationship, reviewer_template_key, reviewer_template_version,
                        reviewer_template_hash, reviewer_configuration_revision,
                        reviewer_configuration_hash, policy_snapshot_id,
                        policy_snapshot_revision, policy_snapshot_hash, context_hash,
                        authority_snapshot, created_at, created_by_principal_id
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, 'CODE_CHANGE', ?, ?, ?, ?, 1,
                        ?, 1, ?, ?, ?, ?, 0, ?, ?, ?, 'SELF_REVIEW', 'reviewer', 1,
                        ?, 1, ?, ?, 1, ?, ?, '{}'::JSONB, ?, ?
                    )
                    """,
                    packageId, organizationId.value(), teamId.value(), workspaceId, projectId,
                    taskId, executionId, UUID.randomUUID(), HASH, UUID.randomUUID(), HASH,
                    UUID.randomUUID(), HASH, HASH, UUID.randomUUID(), HASH, UUID.randomUUID(),
                    principalId, memberId, memberId, HASH, HASH,
                    UUID.randomUUID(), HASH, HASH, at, principalId));
            return packageId;
        });
        UUID requestId = UUID.randomUUID();
        UUID predecessor = lastReviewRequests.get(taskId);
        withoutGuards(() -> {
            jdbc.update("""
                    INSERT INTO crewscope.review_request (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, revision, predecessor_request_id,
                        subject_id, subject_type, subject_hash, context_package_id,
                        context_package_version, context_hash, request_hash, status,
                        invalidation_reason, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'CODE_CHANGE', ?, ?, 1, ?, ?, ?,
                              ?, 0, ?, ?, ?, ?)
                    """,
                    requestId, organizationId.value(), teamId.value(), workspaceId, projectId,
                    taskId, executionId, revision, predecessor, UUID.randomUUID(), HASH,
                    contextId, HASH, HASH, requestStatus,
                    "INVALIDATED".equals(requestStatus) ? "SUBJECT_CHANGED" : null,
                    at, principalId, at, principalId);
            if (withProjection) {
                jdbc.update("""
                        INSERT INTO crewscope.review_request_projection (
                            review_request_id, organization_id, team_id, workspace_id, project_id,
                            task_id, task_execution_id, attempt, request_revision,
                            request_version, request_status, invalidation_reason, context_hash,
                            finding_count, duplicate_observation_count, blocker_count,
                            high_count, latest_decision_id, latest_decision_revision,
                            latest_decision_type, modification_round, projected_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 0, ?, NULL, ?, 0, 0, 0, 0,
                                  ?, 1, ?, ?, ?)
                        """,
                        requestId, organizationId.value(), teamId.value(), workspaceId,
                        projectId, taskId, executionId, revision, requestStatus, HASH,
                        latestDecisionType == null ? null : UUID.randomUUID(),
                        latestDecisionType, modificationRound, at);
            }
        });
        lastReviewRequests.put(taskId, requestId);
    }
}
