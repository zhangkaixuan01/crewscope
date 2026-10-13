package io.crewscope.infrastructure.persistence.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.quota.OrganizationQuotaConfigurationRepository;
import io.crewscope.application.quota.OrganizationTaskIntakeQuotaCounter;
import io.crewscope.application.quota.OrganizationTokenBudgetLedger;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcOrganizationTaskIntakeQuotaCounterAdapter;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * PostgreSQL contract for the V70 adapters: the configuration row's optimistic CAS, the
 * authoritative intake COUNT against the reporting-month window, and the token ledger's
 * upsert/lock/CAS reservation chain with month independence and no lost updates.
 */
@SpringBootTest(
        classes = JdbcOrganizationQuotaPersistenceM12A02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class JdbcOrganizationQuotaPersistenceM12A02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.from(Instant.parse("2026-10-12T12:00:00Z"));
    private static final Instant WINDOW_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-11-01T00:00:00Z");
    private static final String SNAPSHOT_HASH = "a".repeat(64);

    @Autowired private OrganizationQuotaConfigurationRepository configurations;
    @Autowired private OrganizationTaskIntakeQuotaCounter intakeCounter;
    @Autowired private OrganizationTokenBudgetLedger tokenLedger;
    @Autowired private SpringTransactionExecutor transactions;
    @Autowired private JdbcTemplate jdbc;

    private OrganizationId organizationId;
    private PrincipalId principalId;
    private UUID teamId;
    private UUID workspaceId;
    private UUID projectId;
    private final AtomicInteger itemSequence = new AtomicInteger();

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        organizationId = OrganizationId.generate();
        principalId = PrincipalId.generate();
        teamId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status)"
                        + " VALUES (?, ?, 'Quota Team', 'ACTIVE')",
                teamId, organizationId.value());
        jdbc.update("""
                INSERT INTO crewscope.principal (
                    id, organization_id, principal_type, display_name, status
                ) VALUES (?, ?, 'USER', 'Quota Actor', 'ACTIVE')
                """,
                principalId.value(), organizationId.value());
        jdbc.update("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', 'Quota Workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId);
        jdbc.update("""
                INSERT INTO crewscope.work_project (
                    id, organization_id, team_id, workspace_id, project_key, name,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, 'A02', 'Quota Project', ?, ?)
                """,
                projectId, organizationId.value(), teamId, workspaceId,
                principalId.value(), principalId.value());
    }

    @Test
    void configurationRowsMaterializeOnFirstWriteAndUpdateUnderOptimisticVersioning() {
        assertTrue(configurations.findByOrganization(organizationId).isEmpty());

        OrganizationQuotaConfiguration stored = configurations.insert(
                OrganizationQuotaConfiguration.initial(organizationId, 10, 500_000L, principalId, NOW));
        assertEquals(0, stored.version());
        OrganizationQuotaConfiguration reloaded =
                configurations.findByOrganization(organizationId).orElseThrow();
        assertEquals(10, reloaded.monthlyTaskIntakeLimit().orElseThrow());
        assertEquals(500_000L, reloaded.monthlyTokenBudget().orElseThrow());

        OrganizationQuotaConfiguration changed = configurations.update(
                reloaded.changeLimits(0, null, null, principalId, NOW));
        assertEquals(1, changed.version());
        OrganizationQuotaConfiguration reloadedAgain =
                configurations.findByOrganization(organizationId).orElseThrow();
        assertTrue(reloadedAgain.monthlyTaskIntakeLimit().isEmpty());
        assertTrue(reloadedAgain.monthlyTokenBudget().isEmpty());

        // The already-applied successor cannot be persisted twice.
        assertThrows(
                io.crewscope.domain.shared.error.OptimisticLockConflictException.class,
                () -> configurations.update(changed));
    }

    @Test
    void intakeCountingRequiresATransactionAndCountsOnlyTheReportingMonthWindow() {
        seedTask(WINDOW_START.plusSeconds(3_600));
        seedTask(WINDOW_START.plusSeconds(7_200));
        // Outside the month window: last month and next month never count.
        seedTask(WINDOW_START.minusSeconds(86_400));
        seedTask(WINDOW_END.plusSeconds(3_600));

        assertThrows(
                IllegalTransactionStateException.class,
                () -> intakeCounter.check(query(4)));

        assertEquals(
                OrganizationTaskIntakeQuotaCounter.Decision.AVAILABLE,
                transactions.required(() -> intakeCounter.check(query(3))));
        // count == limit is the denial boundary (ClaimQuota form).
        assertEquals(
                OrganizationTaskIntakeQuotaCounter.Decision.INTAKE_LIMIT_REACHED,
                transactions.required(() -> intakeCounter.check(query(2))));
        assertEquals(
                OrganizationTaskIntakeQuotaCounter.Decision.INTAKE_LIMIT_REACHED,
                transactions.required(() -> intakeCounter.check(query(1))));
    }

    @Test
    void tokenReservationsMaterializeTheMonthRowAndAccumulateWithoutRefunds() {
        assertThrows(
                IllegalTransactionStateException.class,
                () -> tokenLedger.reserve(reservation("2026-10", 200L, 1_000L)));

        transactions.required(() -> tokenLedger.reserve(reservation("2026-10", 200L, 1_000L)));
        assertEquals(
                OrganizationTokenBudgetLedger.Decision.RESERVED,
                transactions.required(
                        () -> tokenLedger.reserve(reservation("2026-10", 300L, 1_000L))));

        assertEquals(
                500L,
                jdbc.queryForObject(
                        "SELECT reserved_tokens FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ? AND usage_month = '2026-10'",
                        Long.class,
                        organizationId.value()));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT reservation_sequence FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ? AND usage_month = '2026-10'",
                        Long.class,
                        organizationId.value()));

        // A declined reservation leaves the ledger untouched.
        assertEquals(
                OrganizationTokenBudgetLedger.Decision.BUDGET_EXCEEDED,
                transactions.required(
                        () -> tokenLedger.reserve(reservation("2026-10", 999_999L, 1_000L))));
        assertEquals(
                500L,
                jdbc.queryForObject(
                        "SELECT reserved_tokens FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ? AND usage_month = '2026-10'",
                        Long.class,
                        organizationId.value()));
    }

    @Test
    void eachReportingMonthStartsFromAFreshZeroRow() {
        transactions.required(() -> tokenLedger.reserve(reservation("2026-09", 700L, 1_000L)));
        transactions.required(() -> tokenLedger.reserve(reservation("2026-10", 100L, 1_000L)));

        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ?",
                        Long.class,
                        organizationId.value()));
        assertEquals(
                700L,
                jdbc.queryForObject(
                        "SELECT reserved_tokens FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ? AND usage_month = '2026-09'",
                        Long.class,
                        organizationId.value()));
        assertEquals(
                100L,
                jdbc.queryForObject(
                        "SELECT reserved_tokens FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ? AND usage_month = '2026-10'",
                        Long.class,
                        organizationId.value()));
    }

    @Test
    void anOverflowingAccumulationDeclinesInsteadOfWrapping() {
        transactions.required(() -> tokenLedger.reserve(reservation("2026-10", 100L, Long.MAX_VALUE)));
        jdbc.update(
                "UPDATE crewscope.organization_quota_token_ledger SET reserved_tokens = ?"
                        + " WHERE organization_id = ? AND usage_month = '2026-10'",
                Long.MAX_VALUE - 100L, organizationId.value());

        assertEquals(
                OrganizationTokenBudgetLedger.Decision.BUDGET_EXCEEDED,
                transactions.required(
                        () -> tokenLedger.reserve(reservation("2026-10", 200L, Long.MAX_VALUE))));
    }

    @Test
    void concurrentReservationsSerializeWithoutLosingUpdates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OrganizationTokenBudgetLedger.Decision> first =
                    pool.submit(() -> transactions.required(
                            () -> tokenLedger.reserve(reservation("2026-10", 100L, 1_000L))));
            Future<OrganizationTokenBudgetLedger.Decision> second =
                    pool.submit(() -> transactions.required(
                            () -> tokenLedger.reserve(reservation("2026-10", 300L, 1_000L))));
            assertEquals(OrganizationTokenBudgetLedger.Decision.RESERVED, first.get(30, TimeUnit.SECONDS));
            assertEquals(OrganizationTokenBudgetLedger.Decision.RESERVED, second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(
                400L,
                jdbc.queryForObject(
                        "SELECT reserved_tokens FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ? AND usage_month = '2026-10'",
                        Long.class,
                        organizationId.value()));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT reservation_sequence FROM crewscope.organization_quota_token_ledger"
                                + " WHERE organization_id = ? AND usage_month = '2026-10'",
                        Long.class,
                        organizationId.value()));
    }

    private OrganizationTaskIntakeQuotaCounter.QuotaQuery query(int intakeLimit) {
        return new OrganizationTaskIntakeQuotaCounter.QuotaQuery(
                organizationId, WINDOW_START, WINDOW_END, intakeLimit);
    }

    private OrganizationTokenBudgetLedger.ReservationRequest reservation(
            String usageMonth, long tokens, long budgetLimit) {
        return new OrganizationTokenBudgetLedger.ReservationRequest(
                organizationId, usageMonth, tokens, budgetLimit, principalId);
    }

    private void seedTask(Instant createdAt) {
        java.sql.Timestamp at = java.sql.Timestamp.from(createdAt);
        UUID workItemId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority, due_at,
                    source_provider, created_at, updated_at,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Quota item', 'BACKLOG', 'HIGH', NULL,
                          'CREWSCOPE', ?, ?, ?, ?)
                """,
                workItemId, organizationId.value(), teamId, workspaceId, projectId,
                "A02-" + itemSequence.incrementAndGet(), at, at,
                principalId.value(), principalId.value());
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crewscope.task_responsibility_snapshot (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    snapshot_hash, captured_at, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshotId, organizationId.value(), teamId, workspaceId, projectId,
                workItemId, SNAPSHOT_HASH, at, at, principalId.value(), at, principalId.value());
        jdbc.update("""
                INSERT INTO crewscope.task (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    source_type, source_work_item_version, responsibility_snapshot_id,
                    status, objective, acceptance_criteria,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'A02 objective',
                          '["Accept"]'::JSONB, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId.value(), teamId, workspaceId, projectId,
                workItemId, snapshotId, at, principalId.value(), at, principalId.value());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        JdbcOrganizationQuotaConfigurationRepositoryAdapter.class,
        JdbcOrganizationTaskIntakeQuotaCounterAdapter.class,
        JdbcOrganizationTokenBudgetLedgerAdapter.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
