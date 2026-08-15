package io.crewscope.infrastructure.persistence.taskruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.task.ClaimQuotaRepository;
import io.crewscope.application.task.ClaimSchedulerMetricOutcome;
import io.crewscope.application.task.ClaimSchedulerMetrics;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskClaimBatchResult;
import io.crewscope.application.task.TaskExecutionQueueRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.infrastructure.runtime.DurableTaskClaimScheduler;
import io.crewscope.infrastructure.runtime.SecureClaimTokenGenerator;
import io.crewscope.infrastructure.runtime.TaskClaimSchedulerSpec;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.PostgresAuthoritativeTimeProvider;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/** PostgreSQL evidence for M3-I02 queue fairness, routing, quota and ownership semantics. */
@SpringBootTest(
        classes = DurableTaskClaimSchedulerM3I02IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class DurableTaskClaimSchedulerM3I02IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp READY = UtcTimestamp.parse("2026-08-14T01:05:00Z");
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-14T01:10:00Z");

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutionRepository executionRepository;
    @Autowired private PolicySnapshotRepository policyRepository;
    @Autowired private SafetyEnforcementOverlayRepository overlayRepository;
    @Autowired private ExecutionRuntimeRepository runtimeRepository;
    @Autowired private RuntimeWorkerRepository workerRepository;
    @Autowired private ExecutionLeaseRepository leaseRepository;
    @Autowired private TaskExecutionQueueRepository queueRepository;
    @Autowired private ClaimQuotaRepository quotaRepository;
    @Autowired private AuthoritativeTimeProvider authoritativeTimeProvider;
    @Autowired private TransactionExecutor transactionExecutor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final AtomicReference<UtcTimestamp> now = new AtomicReference<>(NOW);

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        now.set(NOW);
    }

    @Test
    void productionClaimTimeUsesTheLivePostgresClockInsideTheRequiredTransaction() {
        assertThrows(IllegalTransactionStateException.class, authoritativeTimeProvider::now);
        transactionExecutor.required(() -> {
            UtcTimestamp first = authoritativeTimeProvider.now();
            jdbcTemplate.execute("SELECT pg_sleep(0.02)");
            UtcTimestamp second = authoritativeTimeProvider.now();
            assertTrue(first.value().isAfter(java.time.Instant.EPOCH));
            assertTrue(second.compareTo(first) > 0);
            return null;
        });
    }

    @Test
    void claimsFairBoundedBatchAndStoresOnlyTokenHash() {
        var fixture = seedFixture("CLAIM");
        TaskExecution low = persistReady(fixture, 40, READY);
        TaskExecution highOlder = persistReady(fixture, 90, READY);
        TaskExecution highLater = persistReady(
                fixture, 90, UtcTimestamp.parse("2026-08-14T01:06:00Z"));
        persistReady(fixture, 100, UtcTimestamp.parse("2026-08-14T02:00:00Z"));
        RuntimeWorker worker = registerWorker(fixture, "worker-a", fullCapabilities(), 4);
        CollectingMetrics metrics = new CollectingMetrics();
        DurableTaskClaimScheduler scheduler = scheduler(fixture, worker, metrics, 8, 8, 3, 8);

        TaskClaimBatchResult result = scheduler.claim(3);

        assertEquals(List.of(highOlder.id(), highLater.id(), low.id()), result.receipts().stream()
                .map(ClaimReceipt::taskExecutionId).toList());
        assertEquals(3, result.scanned());
        assertEquals(3, metrics.count(ClaimSchedulerMetricOutcome.CLAIMED));
        assertThrows(IllegalArgumentException.class, () -> scheduler.claim(4));

        ClaimReceipt first = result.receipts().get(0);
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT BTRIM(claim_token_hash) FROM crewscope.execution_lease WHERE id = ?",
                String.class,
                first.leaseId().value());
        assertEquals(first.claimToken().hash().value(), storedHash);
        assertNotEquals(first.claimToken().reveal(), storedHash);
        assertFalse(first.toString().contains(first.claimToken().reveal()));
    }

    @Test
    void capabilityMismatchWaitsOnlyWhenNoRegisteredCarrierExists() {
        var fixture = seedFixture("CAP");
        TaskExecution unsupported = persistReady(fixture, 80, READY);
        RuntimeWorker worker = registerWorker(
                fixture,
                "worker-plan-only",
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                4);
        CollectingMetrics metrics = new CollectingMetrics();

        TaskClaimBatchResult result = scheduler(
                fixture, worker, metrics, 8, 8, 2, 8).claim(1);

        assertTrue(result.receipts().isEmpty());
        assertEquals(1, result.waitingRuntime());
        TaskExecution waiting = executionRepository.findById(
                fixture.organizationId, unsupported.id()).orElseThrow();
        assertEquals(TaskExecutionStatus.WAITING, waiting.status());
        assertEquals(TaskExecutionWaitReason.RUNTIME, waiting.waiting().orElseThrow().reason());
        assertEquals(1, metrics.count(ClaimSchedulerMetricOutcome.WAITING_RUNTIME));

        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        var carrierFixture = seedFixture("CARRIER");
        TaskExecution deferred = persistReady(carrierFixture, 80, READY);
        RuntimeWorker capable = registerWorker(
                carrierFixture, "worker-capable", fullCapabilities(), 4);
        RuntimeWorker planOnly = registerAdditionalWorker(
                carrierFixture,
                capable,
                "worker-plan-only",
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                4);

        TaskClaimBatchResult carrierResult = scheduler(
                carrierFixture,
                planOnly,
                new CollectingMetrics(),
                8,
                8,
                1,
                4).claim(1);

        assertEquals(1, carrierResult.capabilityDeferred());
        assertEquals(TaskExecutionStatus.READY, executionRepository.findById(
                carrierFixture.organizationId, deferred.id()).orElseThrow().status());
    }

    @Test
    void enforcesTeamAndRuntimeQuotaAgainstActiveLeases() {
        var teamFixture = seedFixture("TEAMQ");
        persistReady(teamFixture, 90, READY);
        persistReady(teamFixture, 80, READY);
        RuntimeWorker teamWorker = registerWorker(
                teamFixture, "worker-team", fullCapabilities(), 4);
        CollectingMetrics teamMetrics = new CollectingMetrics();

        TaskClaimBatchResult teamLimited = scheduler(
                teamFixture, teamWorker, teamMetrics, 1, 8, 4, 8).claim(2);

        assertEquals(1, teamLimited.receipts().size());
        assertEquals(1, teamLimited.quotaDeferred());
        assertEquals(1, teamMetrics.count(ClaimSchedulerMetricOutcome.TEAM_QUOTA));

        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        var runtimeFixture = seedFixture("RUNQ");
        persistReady(runtimeFixture, 90, READY);
        persistReady(runtimeFixture, 80, READY);
        RuntimeWorker runtimeWorker = registerWorker(
                runtimeFixture, "worker-runtime", fullCapabilities(), 4);
        CollectingMetrics runtimeMetrics = new CollectingMetrics();

        TaskClaimBatchResult runtimeLimited = scheduler(
                runtimeFixture, runtimeWorker, runtimeMetrics, 8, 1, 4, 8).claim(2);

        assertEquals(1, runtimeLimited.receipts().size());
        assertEquals(1, runtimeLimited.quotaDeferred());
        assertEquals(1, runtimeMetrics.count(ClaimSchedulerMetricOutcome.RUNTIME_QUOTA));
    }

    @Test
    void twoWorkersCannotClaimTheSameExecution() throws Exception {
        var fixture = seedFixture("RACE");
        TaskExecution execution = persistReady(fixture, 80, READY);
        RuntimeWorker firstWorker = registerWorker(fixture, "worker-a", fullCapabilities(), 4);
        RuntimeWorker secondWorker = registerAdditionalWorker(
                fixture, firstWorker, "worker-b", fullCapabilities(), 4);
        DurableTaskClaimScheduler first = scheduler(
                fixture, firstWorker, new CollectingMetrics(), 8, 8, 1, 4);
        DurableTaskClaimScheduler second = scheduler(
                fixture, secondWorker, new CollectingMetrics(), 8, 8, 1, 4);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<TaskClaimBatchResult> results = new ArrayList<>();
        try {
            Future<TaskClaimBatchResult> left = executor.submit(() -> {
                start.await();
                return first.claim(1);
            });
            Future<TaskClaimBatchResult> right = executor.submit(() -> {
                start.await();
                return second.claim(1);
            });
            start.countDown();
            results.add(left.get(10, TimeUnit.SECONDS));
            results.add(right.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        List<ClaimReceipt> receipts = results.stream()
                .flatMap(result -> result.receipts().stream()).toList();
        assertEquals(1, receipts.size());
        assertEquals(execution.id(), receipts.get(0).taskExecutionId());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.execution_lease WHERE status = 'ACTIVE'",
                Integer.class));
    }

    @Test
    void reclaimedExecutionGetsNewSecretAndMonotonicFencingToken() {
        var fixture = seedFixture("FENCE");
        TaskExecution execution = persistReady(fixture, 80, READY);
        RuntimeWorker worker = registerWorker(fixture, "worker-a", fullCapabilities(), 4);
        DurableTaskClaimScheduler scheduler = scheduler(
                fixture, worker, new CollectingMetrics(), 8, 8, 1, 4);
        ClaimReceipt first = scheduler.claim(1).receipts().get(0);

        UtcTimestamp recoveryAt = UtcTimestamp.parse("2026-08-14T01:10:01Z");
        TaskExecution claimed = executionRepository.findById(
                fixture.organizationId, execution.id()).orElseThrow();
        TaskExecution recovering = claimed.beginRecovery(
                claimed.version(), fixture.owner, recoveryAt);
        ExecutionLease activeLease = leaseRepository.findActiveByTaskExecution(
                fixture.organizationId, execution.id()).orElseThrow();
        ExecutionLease released = activeLease.release(
                recovering,
                first.ownership(),
                ExecutionLeaseReleaseReason.WORKER_SHUTDOWN,
                activeLease.version(),
                recoveryAt);
        leaseRepository.release(recovering, released);

        UtcTimestamp requeuedAt = UtcTimestamp.parse("2026-08-14T01:10:02Z");
        TaskExecution committedRecovery = executionRepository.findById(
                fixture.organizationId, execution.id()).orElseThrow();
        executionRepository.update(committedRecovery.requeue(
                requeuedAt,
                committedRecovery.version(),
                fixture.owner,
                requeuedAt));
        now.set(UtcTimestamp.parse("2026-08-14T01:10:03Z"));

        ClaimReceipt second = scheduler.claim(1).receipts().get(0);

        assertEquals(first.fencingToken().value() + 1, second.fencingToken().value());
        assertNotEquals(first.claimToken().reveal(), second.claimToken().reveal());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.execution_lease WHERE task_execution_id = ?",
                Integer.class,
                execution.id().value()));
    }

    private M3TaskRuntimePersistenceIntegrationTest.Fixture seedFixture(String key) {
        var fixture = new M3TaskRuntimePersistenceIntegrationTest.Fixture(key);
        fixture.seed(jdbcTemplate);
        return fixture;
    }

    private TaskExecution persistReady(
            M3TaskRuntimePersistenceIntegrationTest.Fixture fixture,
            int priority,
            UtcTimestamp notBefore) {
        Task task = taskRepository.create(fixture.task());
        TaskExecution execution = executionRepository.create(
                fixture.execution(task, priority, notBefore));
        PolicySnapshot policy = policyRepository.create(fixture.policy(task, execution));
        SafetyEnforcementOverlay overlay = overlayRepository.create(
                SafetyEnforcementOverlay.unrestricted(
                        SafetyEnforcementOverlayId.generate(),
                        task,
                        execution,
                        fixture.owner,
                        READY));
        TaskExecution initialized = executionRepository.update(execution.initializePlanningContext(
                policy, overlay, execution.version(), fixture.owner, READY));
        return executionRepository.update(initialized.markReady(
                initialized.version(), fixture.owner, READY));
    }

    private RuntimeWorker registerWorker(
            M3TaskRuntimePersistenceIntegrationTest.Fixture fixture,
            String stableKey,
            RuntimeCapabilities capabilities,
            int capacity) {
        ExecutionRuntime runtime = runtimeRepository.create(ExecutionRuntime.register(
                io.crewscope.domain.runtime.ExecutionRuntimeId.generate(),
                fixture.organizationId,
                fixture.environment,
                "agentscope-java",
                "AgentScope Java",
                "2.0.0",
                capabilities,
                fixture.owner,
                NOW));
        RuntimeWorker registered = workerRepository.create(RuntimeWorker.register(
                io.crewscope.domain.runtime.RuntimeWorkerId.generate(),
                runtime,
                stableKey,
                RuntimeProfile.WORKER,
                capabilities,
                new RuntimeWorkerCapacity(capacity, 0),
                fixture.owner,
                NOW));
        return workerRepository.update(registered.activate(
                registered.version(), fixture.owner, NOW));
    }

    private RuntimeWorker registerAdditionalWorker(
            M3TaskRuntimePersistenceIntegrationTest.Fixture fixture,
            RuntimeWorker existing,
            String stableKey,
            RuntimeCapabilities capabilities,
            int capacity) {
        ExecutionRuntime runtime = runtimeRepository.findById(
                fixture.organizationId, fixture.environment, existing.runtimeId()).orElseThrow();
        RuntimeWorker registered = workerRepository.create(RuntimeWorker.register(
                io.crewscope.domain.runtime.RuntimeWorkerId.generate(),
                runtime,
                stableKey,
                RuntimeProfile.WORKER,
                capabilities,
                new RuntimeWorkerCapacity(capacity, 0),
                fixture.owner,
                NOW));
        return workerRepository.update(registered.activate(
                registered.version(), fixture.owner, NOW));
    }

    private DurableTaskClaimScheduler scheduler(
            M3TaskRuntimePersistenceIntegrationTest.Fixture fixture,
            RuntimeWorker worker,
            ClaimSchedulerMetrics metrics,
            int teamLimit,
            int runtimeLimit,
            int batchLimit,
            int scanLimit) {
        return new DurableTaskClaimScheduler(
                runtimeRepository,
                workerRepository,
                queueRepository,
                executionRepository,
                policyRepository,
                leaseRepository,
                quotaRepository,
                transactionExecutor,
                now::get,
                new SecureClaimTokenGenerator(),
                metrics,
                new TaskClaimSchedulerSpec(
                        fixture.organizationId,
                        fixture.environment,
                        "agentscope-java",
                        worker.stableKey(),
                        fixture.owner,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        teamLimit,
                        runtimeLimit,
                        batchLimit,
                        scanLimit));
    }

    private RuntimeCapabilities fullCapabilities() {
        return RuntimeCapabilities.of(
                RuntimeCapability.TASK_EXECUTION,
                RuntimeCapability.STREAMING,
                RuntimeCapability.DURABLE_EVENT_STREAM,
                RuntimeCapability.PAUSE_RESUME,
                RuntimeCapability.CANCEL,
                RuntimeCapability.SESSION_STATE,
                RuntimeCapability.PLAN,
                RuntimeCapability.STRUCTURED_OUTPUT);
    }

    private static final class CollectingMetrics implements ClaimSchedulerMetrics {

        private final Map<ClaimSchedulerMetricOutcome, Long> counts =
                new EnumMap<>(ClaimSchedulerMetricOutcome.class);

        @Override
        public synchronized void record(ClaimSchedulerMetricOutcome outcome, long amount) {
            counts.merge(outcome, amount, Long::sum);
        }

        synchronized long count(ClaimSchedulerMetricOutcome outcome) {
            return counts.getOrDefault(outcome, 0L);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "io.crewscope.infrastructure.persistence")
    @Import({
        TaskRuntimePersistenceMapper.class,
        TaskRuntimeExtendedPersistenceMapper.class,
        TaskRuntimeJpaSupport.class,
        JpaTaskRuntimeRepositoryAdapter.class,
        JdbcExecutionLeaseRepositoryAdapter.class,
        JdbcTaskExecutionQueueRepositoryAdapter.class,
        JdbcClaimQuotaRepositoryAdapter.class,
        JpaPolicySnapshotRepositoryAdapter.class,
        JpaSafetyEnforcementOverlayRepositoryAdapter.class,
        PostgresAuthoritativeTimeProvider.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
