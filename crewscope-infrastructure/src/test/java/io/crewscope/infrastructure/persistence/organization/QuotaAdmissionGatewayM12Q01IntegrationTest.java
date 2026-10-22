package io.crewscope.infrastructure.persistence.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.crewscope.application.quota.OrganizationQuotaAdmissionService;
import io.crewscope.application.quota.OrganizationQuotaConfigurationRepository;
import io.crewscope.application.quota.OrganizationTaskIntakeQuotaCounter;
import io.crewscope.application.quota.OrganizationTokenBudgetLedger;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedPersistenceTestApplication;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M12-Q01 hardening face for the quota admission seam: the assembled
 * {@link OrganizationQuotaAdmissionService} over the real V70 adapters, inside the task-creation
 * transaction shape it was mounted into (A02: admission after input validation, before the task
 * row inserts). The B-layer matrix proved the mounting semantics against fakes and the A02
 * persistence contract proved each adapter alone; what only this test can pin is the
 * <em>composed</em> behaviour on real PostgreSQL:
 *
 * <ul>
 *   <li><b>Concurrent intake admissions against a tight limit admit exactly the budget</b> — the
 *       advisory transaction lock plus the authoritative same-transaction task COUNT must
 *       serialise rivals, otherwise an admission-only test could never fail (the count only
 *       moves when the task row lands in the same transaction).
 *   <li><b>Concurrent token reservations reserve exactly the budget</b> — the FOR UPDATE ledger
 *       chain under service arbitration.
 *   <li><b>An aborted admission transaction leaves no orphan reservation</b> — ADR-037's "no
 *       sweeper" stance (deviation 1) rests on reservations vanishing with the transaction; the
 *       rollback is exercised here, not merely stated.
 *   <li><b>Denials carry the task-creation action text</b> — the two constants A02/F02 pinned
 *       client-side, asserted verbatim against the Java source.
 * </ul>
 */
@SpringBootTest(
        classes = SharedPersistenceTestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class QuotaAdmissionGatewayM12Q01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.from(Instant.parse("2026-10-16T09:00:00Z"));
    private static final String SNAPSHOT_HASH = "a".repeat(64);
    /**
     * Verbatim from {@code OrganizationQuotaAdmissionService} (package-private there): the F02
     * frontend matches these strings to route quota denials to the localized guidance, so any
     * drift is a breaking contract change, not a cosmetic edit.
     */
    private static final String INTAKE_ACTION =
            "create more agent tasks this month (organization monthly task intake limit reached)";
    private static final String TOKEN_ACTION =
            "create an agent task reserving additional model tokens this month "
            + "(organization monthly token budget reached)";

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
    private OrganizationQuotaAdmissionService admission;
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
                ) VALUES (?, ?, ?, ?, 'Q01', 'Quota Project', ?, ?)
                """,
                projectId, organizationId.value(), teamId, workspaceId,
                principalId.value(), principalId.value());
        admission = new OrganizationQuotaAdmissionService(
                configurations, intakeCounter, tokenLedger, ZoneId.of("UTC"));
    }

    @Test
    void concurrentIntakeAdmissionsAdmitExactlyTheConfiguredBudget() throws Exception {
        configurations.insert(OrganizationQuotaConfiguration.initial(
                organizationId, 3, null, principalId, NOW));

        List<Object> outcomes = runConcurrently(4, this::admitAndCreateTask);

        long admitted = outcomes.stream().filter(outcome -> outcome == null).count();
        assertEquals(3L, admitted, "exactly the configured budget may pass, the rest must deny");
        PolicyDeniedException denied = (PolicyDeniedException) outcomes.stream()
                .filter(outcome -> outcome instanceof PolicyDeniedException)
                .findFirst()
                .orElseThrow();
        assertEquals(INTAKE_ACTION, denied.error().details().get("action"));
        assertEquals(3L, countTasks());
    }

    @Test
    void concurrentTokenReservationsReserveExactlyTheConfiguredBudget() throws Exception {
        configurations.insert(OrganizationQuotaConfiguration.initial(
                organizationId, null, 500L, principalId, NOW));

        List<Object> outcomes = runConcurrently(4, this::admitAndCreateTask);

        long admitted = outcomes.stream().filter(outcome -> outcome == null).count();
        assertEquals(2L, admitted, "500 budget / 200 max tokens per task admits exactly two");
        assertEquals(
                2L,
                outcomes.stream().filter(PolicyDeniedException.class::isInstance).count(),
                "the third and fourth rival both exceed the remaining budget and must deny");
        assertEquals(400L, reservedTokens());
        assertEquals(2L, countTasks());
    }

    @Test
    void anAbortedAdmissionTransactionLeavesNoOrphanReservation() {
        configurations.insert(OrganizationQuotaConfiguration.initial(
                organizationId, null, 500L, principalId, NOW));

        assertInstanceOf(
                IllegalStateException.class,
                runInCallerThread(() -> transactions.required(this::admitThenFail)));

        // ADR-037 deviation 1 relies on this: the reservation lives and dies with the creation
        // transaction, so a crash cannot strand a phantom budget hold and no sweeper exists.
        assertEquals(0L, reservedTokens());
        assertEquals(0L, countTasks());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.organization_quota_token_ledger",
                        Integer.class),
                "no ledger row may survive the rollback");
    }

    @Test
    void aReservationBeyondTheBudgetCarriesTheReservationAction() {
        // The intake action text is pinned by the concurrent case above; a single task whose
        // max-token bound exceeds the whole budget covers the token dimension. Limits below
        // MIN_LIMIT are invalid rows, so a tight-but-legal budget stands in for an exhausted one.
        configurations.insert(OrganizationQuotaConfiguration.initial(
                organizationId, null, 100L, principalId, NOW));

        PolicyDeniedException denied = assertInstanceOf(
                PolicyDeniedException.class,
                runInCallerThread(() -> transactions.required(() -> {
                    admission.requireAdmission(request(200L));
                    return null;
                })));

        assertEquals(TOKEN_ACTION, denied.error().details().get("action"));
        assertEquals(0L, reservedTokens());
    }

    /** The mounted shape (A02): admission plus the task-row insert share one transaction. */
    private Void admitAndCreateTask() {
        return transactions.required(() -> {
            admission.requireAdmission(request(200L));
            seedTask();
            return null;
        });
    }

    /** A creation whose post-admission pipeline fails: the whole transaction must roll back. */
    private Void admitThenFail() {
        admission.requireAdmission(request(200L));
        seedTask();
        throw new IllegalStateException("task insert pipeline failed after admission");
    }

    private OrganizationQuotaAdmissionService.OrganizationQuotaAdmissionRequest request(
            long taskMaxTokens) {
        return new OrganizationQuotaAdmissionService.OrganizationQuotaAdmissionRequest(
                organizationId, principalId, taskMaxTokens, NOW);
    }

    /**
     * Races {@code rival} transactions through a start barrier so the advisory-lock windows
     * genuinely overlap; each outcome surfaces as {@code null} (admitted) or the thrown
     * denial/failure, unwrapped from its future.
     */
    private List<Object> runConcurrently(int rivals, Supplier<Void> rival) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(rivals);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < rivals; index += 1) {
                futures.add(pool.submit(() -> {
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate never opened");
                    }
                    return rival.get();
                }));
            }
            startGate.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    outcomes.add(null);
                } catch (java.util.concurrent.ExecutionException execution) {
                    outcomes.add(execution.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private Object runInCallerThread(Callable<Object> operation) {
        try {
            return operation.call();
        } catch (RuntimeException failure) {
            return failure;
        } catch (Exception failure) {
            throw new IllegalStateException("unexpected checked failure", failure);
        }
    }

    private long countTasks() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.task WHERE organization_id = ?",
                Long.class,
                organizationId.value());
    }

    /** Empty-safe: a denied or rolled-back admission never creates the ledger row at all. */
    private long reservedTokens() {
        List<Long> rows = jdbc.query(
                "SELECT reserved_tokens FROM crewscope.organization_quota_token_ledger"
                        + " WHERE organization_id = ? AND usage_month = '2026-10'",
                (resultSet, rowNum) -> resultSet.getLong(1),
                organizationId.value());
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /** The same three-row insert chain the A02 persistence contract seeds its COUNT window with. */
    private void seedTask() {
        java.sql.Timestamp at = java.sql.Timestamp.from(NOW.value());
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
                "Q01-" + itemSequence.incrementAndGet(), at, at,
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
                ) VALUES (?, ?, ?, ?, ?, ?, 'WORK_ITEM', 0, ?, 'CREATED', 'Q01 objective',
                          '["Accept"]'::JSONB, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId.value(), teamId, workspaceId, projectId,
                workItemId, snapshotId, at, principalId.value(), at, principalId.value());
    }
}
