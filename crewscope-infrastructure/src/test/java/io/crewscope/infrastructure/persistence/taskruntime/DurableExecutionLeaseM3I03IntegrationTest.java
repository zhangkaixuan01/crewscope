package io.crewscope.infrastructure.persistence.taskruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.LeaseCommandScope;
import io.crewscope.application.task.LeaseCoordinatorMetrics;
import io.crewscope.application.task.LeaseExecutionCommand;
import io.crewscope.application.task.LeaseHeartbeatCommand;
import io.crewscope.application.task.LeaseMutationResult;
import io.crewscope.application.task.LeaseReleaseCommand;
import io.crewscope.application.task.LeaseSweepResult;
import io.crewscope.application.task.LeaseTransitionCommand;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimToken;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.LeaseOwnership;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.runtime.DurableExecutionLeaseSweeper;
import io.crewscope.infrastructure.runtime.DurableTaskExecutionLeaseCoordinator;
import io.crewscope.infrastructure.runtime.ExecutionLeaseCoordinatorSpec;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Duration;
import java.util.Optional;
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

/** PostgreSQL fault evidence for M3-I03 renewal, fencing and expiry recovery. */
@SpringBootTest(
        classes = DurableExecutionLeaseM3I03IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class DurableExecutionLeaseM3I03IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp CLAIMED_AT =
            UtcTimestamp.parse("2026-08-15T01:00:00Z");
    private static final ClaimToken FIRST_TOKEN =
            new ClaimToken("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq");
    private static final ClaimToken SECOND_TOKEN =
            new ClaimToken("BCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqr");
    private static final LeaseCoordinatorMetrics NOOP_METRICS =
            (operation, outcome, amount) -> {};

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutionRepository executionRepository;
    @Autowired private io.crewscope.application.runtime.ExecutionRuntimeRepository runtimeRepository;
    @Autowired private io.crewscope.application.runtime.RuntimeWorkerRepository workerRepository;
    @Autowired private ExecutionLeaseRepository leaseRepository;
    @Autowired private DomainEventStore domainEventStore;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private TaskEventRepository taskEventRepository;
    @Autowired private TransactionExecutor transactionExecutor;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void renewsPrepareAndRunUsingPhaseTtlWithoutChangingTaskVersion() {
        LeaseFixture fixture = claim("PHASE", plus(CLAIMED_AT, Duration.ofSeconds(30)), FIRST_TOKEN);
        AtomicReference<UtcTimestamp> now = new AtomicReference<>(
                plus(CLAIMED_AT, Duration.ofSeconds(5)));
        DurableTaskExecutionLeaseCoordinator coordinator = coordinator(fixture, now, 30, 45);

        TaskExecution preparing = coordinator.updateOwned(
                fixture.executionCommand(fixture.executionVersion()),
                TaskExecution::beginPreparing);
        long preparingVersion = preparing.version();
        now.set(plus(CLAIMED_AT, Duration.ofSeconds(10)));
        ExecutionLease prepareHeartbeat = coordinator.heartbeat(
                fixture.heartbeat(0));

        assertEquals(ExecutionLeasePhase.PREPARE, prepareHeartbeat.phase());
        assertEquals(plus(now.get(), Duration.ofSeconds(30)), prepareHeartbeat.expiresAt());
        assertEquals(preparingVersion, executionRepository.findById(
                fixture.organizationId(), fixture.executionId()).orElseThrow().version());

        now.set(plus(CLAIMED_AT, Duration.ofSeconds(15)));
        LeaseMutationResult running = coordinator.beginRun(
                fixture.transitionCommand(preparingVersion, prepareHeartbeat.version()));
        assertEquals(ExecutionLeasePhase.RUN, running.lease().phase());
        assertEquals(plus(now.get(), Duration.ofSeconds(45)), running.lease().expiresAt());

        long runningVersion = running.execution().version();
        now.set(plus(CLAIMED_AT, Duration.ofSeconds(20)));
        ExecutionLease runHeartbeat = coordinator.heartbeat(
                fixture.heartbeat(running.lease().version()));
        assertEquals(ExecutionLeasePhase.RUN, runHeartbeat.phase());
        assertEquals(plus(now.get(), Duration.ofSeconds(45)), runHeartbeat.expiresAt());
        assertEquals(runningVersion, executionRepository.findById(
                fixture.organizationId(), fixture.executionId()).orElseThrow().version());
        LeaseMutationResult completed = coordinator.release(LeaseReleaseCommand.simple(
                fixture.transitionCommand(runningVersion, runHeartbeat.version()),
                ExecutionLeaseReleaseReason.COMPLETED));
        assertEquals(TaskExecutionStatus.COMPLETED, completed.execution().status());
        assertEquals(
                ExecutionLeaseReleaseReason.COMPLETED,
                completed.lease().release().orElseThrow().reason());
    }

    @Test
    void rejectsWrongTokenExpiredHeartbeatAndOldOwnerAfterReclaim() {
        UtcTimestamp expiresAt = plus(CLAIMED_AT, Duration.ofSeconds(30));
        LeaseFixture fixture = claim("FENCE", expiresAt, FIRST_TOKEN);
        AtomicReference<UtcTimestamp> now = new AtomicReference<>(
                plus(CLAIMED_AT, Duration.ofSeconds(5)));
        DurableTaskExecutionLeaseCoordinator coordinator = coordinator(fixture, now, 30, 30);
        LeaseOwnership wrongOwnership = new LeaseOwnership(
                fixture.executionId(),
                1,
                fixture.runtimeId(),
                fixture.workerId(),
                SECOND_TOKEN.hash(),
                FencingToken.initial());
        LeaseExecutionCommand wrongCommand = new LeaseExecutionCommand(
                new LeaseCommandScope(
                        fixture.organizationId(),
                        fixture.environment(),
                        fixture.leaseId(),
                        wrongOwnership),
                fixture.executionVersion());
        assertThrows(RuntimeException.class, () -> coordinator.beginPreparing(wrongCommand));

        now.set(expiresAt);
        assertThrows(RuntimeException.class, () -> coordinator.heartbeat(fixture.heartbeat(0)));
        LeaseSweepResult swept = sweeper(fixture, now).sweep(10);
        assertEquals(1, swept.recovered().size());

        UtcTimestamp requeuedAt = plus(expiresAt, Duration.ofSeconds(1));
        TaskExecution recovering = executionRepository.findById(
                fixture.organizationId(), fixture.executionId()).orElseThrow();
        TaskExecution ready = executionRepository.update(recovering.requeue(
                requeuedAt, recovering.version(), fixture.actor(), requeuedAt));
        LeaseFixture replacement = reclaim(fixture, ready, SECOND_TOKEN, requeuedAt);

        now.set(plus(requeuedAt, Duration.ofSeconds(1)));
        assertThrows(RuntimeException.class, () -> coordinator.beginPreparing(
                fixture.executionCommand(fixture.executionVersion())));
        assertEquals(TaskExecutionStatus.PREPARING,
                coordinator(replacement, now, 30, 30)
                        .beginPreparing(replacement.executionCommand(
                                replacement.executionVersion())).status());
        assertEquals(2, replacement.ownership().fencingToken().value());
    }

    @Test
    void locksLeaseOwnershipOnlyInsideTheCallingTransaction() {
        LeaseFixture fixture = claim(
                "LOCK", plus(CLAIMED_AT, Duration.ofSeconds(30)), FIRST_TOKEN);

        assertThrows(IllegalTransactionStateException.class, () ->
                leaseRepository.findByIdForUpdate(
                        fixture.organizationId(), fixture.environment(), fixture.leaseId()));

        ExecutionLease locked = transactionExecutor.required(() ->
                leaseRepository.findByIdForUpdate(
                                fixture.organizationId(),
                                fixture.environment(),
                                fixture.leaseId())
                        .orElseThrow());
        assertEquals(fixture.leaseId(), locked.id());
        assertTrue(locked.owns(
                fixture.ownership(), plus(CLAIMED_AT, Duration.ofSeconds(1))));
    }

    @Test
    void explicitCompletionAndSweeperRaceCommitExactlyOneReleaseFact() throws Exception {
        UtcTimestamp expiresAt = plus(CLAIMED_AT, Duration.ofSeconds(30));
        LeaseFixture fixture = claim("RACE", expiresAt, FIRST_TOKEN);
        AtomicReference<UtcTimestamp> workerNow = new AtomicReference<>(
                plus(CLAIMED_AT, Duration.ofSeconds(5)));
        DurableTaskExecutionLeaseCoordinator coordinator = coordinator(fixture, workerNow, 30, 30);
        TaskExecution preparing = coordinator.beginPreparing(
                fixture.executionCommand(fixture.executionVersion()));
        workerNow.set(plus(CLAIMED_AT, Duration.ofSeconds(10)));
        LeaseMutationResult running = coordinator.beginRun(
                fixture.transitionCommand(preparing.version(), 0));

        AtomicReference<UtcTimestamp> completeTime = new AtomicReference<>(
                plus(CLAIMED_AT, Duration.ofSeconds(29)));
        AtomicReference<UtcTimestamp> sweepTime = new AtomicReference<>(expiresAt);
        DurableTaskExecutionLeaseCoordinator completing =
                coordinator(fixture, completeTime, 30, 30);
        DurableExecutionLeaseSweeper sweeper = sweeper(fixture, sweepTime);
        LeaseReleaseCommand complete = LeaseReleaseCommand.simple(
                fixture.transitionCommand(
                        running.execution().version(), running.lease().version()),
                ExecutionLeaseReleaseReason.COMPLETED);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> completeResult = pool.submit(() -> attempt(start, () ->
                    completing.release(complete)));
            Future<Boolean> sweepResult = pool.submit(() -> attempt(start, () -> sweeper.sweep(1)));
            start.countDown();
            completeResult.get(10, TimeUnit.SECONDS);
            sweepResult.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ExecutionLease committedLease = leaseRepository.findById(
                fixture.organizationId(), fixture.environment(), fixture.leaseId()).orElseThrow();
        TaskExecution committedExecution = executionRepository.findById(
                fixture.organizationId(), fixture.executionId()).orElseThrow();
        ExecutionLeaseReleaseReason reason = committedLease.release().orElseThrow().reason();
        assertTrue(reason == ExecutionLeaseReleaseReason.COMPLETED
                || reason == ExecutionLeaseReleaseReason.EXPIRED);
        assertEquals(
                reason == ExecutionLeaseReleaseReason.COMPLETED
                        ? TaskExecutionStatus.COMPLETED
                        : TaskExecutionStatus.RECOVERING,
                committedExecution.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.execution_lease WHERE id = ? AND status = 'RELEASED'",
                Integer.class,
                fixture.leaseId().value()));
    }

    @Test
    void tenCompleteSweeperRacesAlwaysCommitOneConsistentTerminalFact() throws Exception {
        int consistentResults = 0;
        for (int sample = 1; sample <= 10; sample++) {
            UtcTimestamp expiresAt = plus(CLAIMED_AT, Duration.ofSeconds(30));
            LeaseFixture fixture = claim(
                    "Q02R" + sample, expiresAt, fixedClaimToken("RACE", sample));
            AtomicReference<UtcTimestamp> workerNow = new AtomicReference<>(
                    plus(CLAIMED_AT, Duration.ofSeconds(5)));
            DurableTaskExecutionLeaseCoordinator coordinator =
                    coordinator(fixture, workerNow, 30, 30);
            TaskExecution preparing = coordinator.beginPreparing(
                    fixture.executionCommand(fixture.executionVersion()));
            workerNow.set(plus(CLAIMED_AT, Duration.ofSeconds(10)));
            LeaseMutationResult running = coordinator.beginRun(
                    fixture.transitionCommand(preparing.version(), 0));

            AtomicReference<UtcTimestamp> completeTime = new AtomicReference<>(
                    plus(CLAIMED_AT, Duration.ofSeconds(29)));
            AtomicReference<UtcTimestamp> sweepTime = new AtomicReference<>(expiresAt);
            LeaseReleaseCommand complete = LeaseReleaseCommand.simple(
                    fixture.transitionCommand(
                            running.execution().version(), running.lease().version()),
                    ExecutionLeaseReleaseReason.COMPLETED);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> completeResult = pool.submit(() -> attempt(start, () ->
                        coordinator(fixture, completeTime, 30, 30).release(complete)));
                Future<Boolean> sweepResult = pool.submit(() -> attempt(start, () ->
                        sweeper(fixture, sweepTime).sweep(1)));
                start.countDown();
                completeResult.get(10, TimeUnit.SECONDS);
                sweepResult.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            ExecutionLease committedLease = leaseRepository.findById(
                    fixture.organizationId(), fixture.environment(), fixture.leaseId())
                    .orElseThrow();
            TaskExecution committedExecution = executionRepository.findById(
                    fixture.organizationId(), fixture.executionId()).orElseThrow();
            ExecutionLeaseReleaseReason reason =
                    committedLease.release().orElseThrow().reason();
            TaskExecutionStatus expectedStatus = reason == ExecutionLeaseReleaseReason.COMPLETED
                    ? TaskExecutionStatus.COMPLETED
                    : TaskExecutionStatus.RECOVERING;
            assertTrue(reason == ExecutionLeaseReleaseReason.COMPLETED
                    || reason == ExecutionLeaseReleaseReason.EXPIRED);
            assertEquals(expectedStatus, committedExecution.status());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM crewscope.execution_lease "
                            + "WHERE id = ? AND status = 'RELEASED'",
                    Integer.class,
                    fixture.leaseId().value()));
            consistentResults++;
        }

        assertEquals(10, consistentResults);
    }

    @Test
    void tenLostHeartbeatSamplesRecoverOnceAndRejectEveryOldOwnerMutation() {
        int recovered = 0;
        int oldOwnerMutationSuccesses = 0;
        for (int sample = 1; sample <= 10; sample++) {
            UtcTimestamp expiresAt = plus(CLAIMED_AT, Duration.ofSeconds(30));
            LeaseFixture fixture = claim(
                    "Q02H" + sample, expiresAt, fixedClaimToken("HEARTBEAT", sample));
            AtomicReference<UtcTimestamp> now = new AtomicReference<>(expiresAt);
            DurableTaskExecutionLeaseCoordinator oldOwner = coordinator(fixture, now, 30, 30);

            LeaseSweepResult result = sweeper(fixture, now).sweep(1);
            assertEquals(1, result.recovered().size());
            ExecutionLease expiredLease = leaseRepository.findById(
                    fixture.organizationId(), fixture.environment(), fixture.leaseId())
                    .orElseThrow();
            TaskExecution recovering = executionRepository.findById(
                    fixture.organizationId(), fixture.executionId()).orElseThrow();

            // Each operation carries the expired Owner coordinates and must fail before a write.
            oldOwnerMutationSuccesses += succeeds(() -> oldOwner.heartbeat(fixture.heartbeat(0)));
            oldOwnerMutationSuccesses += succeeds(() -> oldOwner.beginPreparing(
                    fixture.executionCommand(fixture.executionVersion())));
            oldOwnerMutationSuccesses += succeeds(() -> oldOwner.beginRun(
                    fixture.transitionCommand(fixture.executionVersion(), 0)));
            oldOwnerMutationSuccesses += succeeds(() -> oldOwner.release(
                    LeaseReleaseCommand.simple(
                            fixture.transitionCommand(fixture.executionVersion(), 0),
                            ExecutionLeaseReleaseReason.COMPLETED)));

            ExecutionLease afterRejectedWrites = leaseRepository.findById(
                    fixture.organizationId(), fixture.environment(), fixture.leaseId())
                    .orElseThrow();
            TaskExecution executionAfterRejectedWrites = executionRepository.findById(
                    fixture.organizationId(), fixture.executionId()).orElseThrow();
            assertEquals(expiredLease.version(), afterRejectedWrites.version());
            assertEquals(
                    expiredLease.release().orElseThrow().reason(),
                    afterRejectedWrites.release().orElseThrow().reason());
            assertEquals(recovering.version(), executionAfterRejectedWrites.version());
            assertEquals(TaskExecutionStatus.RECOVERING, executionAfterRejectedWrites.status());
            recovered++;
        }

        assertEquals(10, recovered);
        assertEquals(0, oldOwnerMutationSuccesses);
    }

    @Test
    void repeatedAndConcurrentSweepsPublishOneRecoveryEventAndOutboxFact() throws Exception {
        UtcTimestamp expiresAt = plus(CLAIMED_AT, Duration.ofSeconds(30));
        LeaseFixture fixture = claim("SWEEP", expiresAt, FIRST_TOKEN);
        AtomicReference<UtcTimestamp> now = new AtomicReference<>(expiresAt);
        DurableExecutionLeaseSweeper first = sweeper(fixture, now);
        DurableExecutionLeaseSweeper second = sweeper(fixture, now);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int recovered;
        try {
            Future<LeaseSweepResult> left = pool.submit(() -> {
                start.await();
                return first.sweep(1);
            });
            Future<LeaseSweepResult> right = pool.submit(() -> {
                start.await();
                return second.sweep(1);
            });
            start.countDown();
            recovered = left.get(10, TimeUnit.SECONDS).recovered().size()
                    + right.get(10, TimeUnit.SECONDS).recovered().size();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, recovered);
        assertTrue(first.sweep(1).recovered().isEmpty());
        assertEquals(TaskExecutionStatus.RECOVERING, executionRepository.findById(
                fixture.organizationId(), fixture.executionId()).orElseThrow().status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_type = ?",
                Integer.class,
                DurableExecutionLeaseSweeper.RECOVERY_EVENT_TYPE));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.outbox_event",
                Integer.class));
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM crewscope.domain_event WHERE event_type = ?",
                String.class,
                DurableExecutionLeaseSweeper.RECOVERY_EVENT_TYPE);
        assertFalse(payload.contains(FIRST_TOKEN.reveal()));
    }

    @Test
    void startupScanLocksExpiredRecoveringAttemptBeforeRequeue() {
        UtcTimestamp expiresAt = plus(CLAIMED_AT, Duration.ofSeconds(30));
        LeaseFixture fixture = claim("STARTUP", expiresAt, FIRST_TOKEN);
        AtomicReference<UtcTimestamp> now = new AtomicReference<>(expiresAt);

        assertEquals(1, sweeper(fixture, now).sweep(10).recovered().size());
        TaskExecution ready = transactionExecutor.required(() -> {
            java.util.List<TaskExecution> recovering =
                    executionRepository.findRecoveringForUpdate(fixture.organizationId(), 10);
            assertEquals(java.util.List.of(fixture.executionId()),
                    recovering.stream().map(TaskExecution::id).toList());
            TaskExecution current = recovering.get(0);
            return executionRepository.update(current.requeue(
                    plus(expiresAt, Duration.ofSeconds(1)),
                    current.version(),
                    fixture.actor(),
                    plus(expiresAt, Duration.ofSeconds(1))));
        });

        assertEquals(TaskExecutionStatus.READY, ready.status());
        assertTrue(leaseRepository.findActiveByTaskExecution(
                fixture.organizationId(), fixture.executionId()).isEmpty());
    }

    private LeaseFixture claim(String key, UtcTimestamp expiresAt, ClaimToken token) {
        M3TaskRuntimePersistenceIntegrationTest.Fixture fixture =
                new M3TaskRuntimePersistenceIntegrationTest.Fixture(key);
        fixture.seed(jdbc);
        Task task = taskRepository.create(fixture.task());
        TaskExecution created = executionRepository.create(
                fixture.execution(task, 50, CLAIMED_AT));
        TaskExecution ready = executionRepository.update(created.markReady(
                created.version(), fixture.owner, CLAIMED_AT));
        RuntimeCapabilities capabilities = fixture.capabilities();
        ExecutionRuntime runtime = runtimeRepository.create(ExecutionRuntime.register(
                ExecutionRuntimeId.generate(),
                fixture.organizationId,
                fixture.environment,
                "agentscope-java",
                "AgentScope Java",
                "2.0.0",
                capabilities,
                fixture.owner,
                CLAIMED_AT));
        RuntimeWorker registered = workerRepository.create(RuntimeWorker.register(
                RuntimeWorkerId.generate(),
                runtime,
                "worker-" + key.toLowerCase(),
                RuntimeProfile.WORKER,
                capabilities,
                new RuntimeWorkerCapacity(4, 0),
                fixture.owner,
                CLAIMED_AT));
        RuntimeWorker worker = workerRepository.update(registered.activate(
                registered.version(), fixture.owner, CLAIMED_AT));
        TaskExecution claimed = ready.claim(ready.version(), fixture.owner, CLAIMED_AT);
        ExecutionLease lease = ExecutionLease.acquire(
                ExecutionLeaseId.generate(),
                claimed,
                runtime,
                worker,
                capabilities,
                Duration.ofSeconds(30),
                token,
                CLAIMED_AT,
                expiresAt);
        leaseRepository.acquire(claimed, lease);
        return LeaseFixture.from(fixture, claimed, lease, token);
    }

    private LeaseFixture reclaim(
            LeaseFixture fixture,
            TaskExecution ready,
            ClaimToken token,
            UtcTimestamp claimedAt) {
        ExecutionRuntime runtime = runtimeRepository.findById(
                        fixture.organizationId(), fixture.environment(), fixture.runtimeId())
                .orElseThrow();
        RuntimeWorker worker = workerRepository.findById(
                        fixture.organizationId(), fixture.environment(), fixture.workerId())
                .orElseThrow();
        TaskExecution claimed = ready.claim(ready.version(), fixture.actor(), claimedAt);
        ExecutionLease lease = ExecutionLease.acquire(
                ExecutionLeaseId.generate(),
                claimed,
                runtime,
                worker,
                fixture.capabilities(),
                Duration.ofMinutes(2),
                token,
                claimedAt,
                plus(claimedAt, Duration.ofSeconds(30)));
        leaseRepository.acquire(claimed, lease);
        return new LeaseFixture(
                fixture.organizationId(),
                fixture.environment(),
                fixture.actor(),
                fixture.capabilities(),
                claimed,
                lease,
                token);
    }

    private DurableTaskExecutionLeaseCoordinator coordinator(
            LeaseFixture fixture,
            AtomicReference<UtcTimestamp> now,
            long prepareSeconds,
            long runSeconds) {
        return new DurableTaskExecutionLeaseCoordinator(
                executionRepository,
                leaseRepository,
                transactionExecutor,
                now::get,
                NOOP_METRICS,
                spec(fixture, prepareSeconds, runSeconds));
    }

    private DurableExecutionLeaseSweeper sweeper(
            LeaseFixture fixture, AtomicReference<UtcTimestamp> now) {
        return new DurableExecutionLeaseSweeper(
                executionRepository,
                leaseRepository,
                domainEventStore,
                taskEventRepository,
                outboxRepository,
                transactionExecutor,
                now::get,
                NOOP_METRICS,
                spec(fixture, 30, 30));
    }

    private ExecutionLeaseCoordinatorSpec spec(
            LeaseFixture fixture, long prepareSeconds, long runSeconds) {
        return new ExecutionLeaseCoordinatorSpec(
                fixture.organizationId(),
                fixture.environment(),
                fixture.actor(),
                Duration.ofSeconds(prepareSeconds),
                Duration.ofSeconds(runSeconds),
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                100);
    }

    private static boolean attempt(CountDownLatch start, Runnable action) {
        try {
            start.await();
            action.run();
            return true;
        } catch (RuntimeException failure) {
            return false;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int succeeds(Runnable action) {
        try {
            action.run();
            return 1;
        } catch (RuntimeException expected) {
            return 0;
        }
    }

    private static ClaimToken fixedClaimToken(String matrix, int sample) {
        return new ClaimToken("M3Q02_" + matrix + "_" + sample + "_" + "A".repeat(43));
    }

    private static UtcTimestamp plus(UtcTimestamp value, Duration duration) {
        return UtcTimestamp.from(value.value().plus(duration));
    }

    private record LeaseFixture(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            io.crewscope.domain.runtime.RuntimeEnvironment environment,
            io.crewscope.domain.identity.Principal actor,
            RuntimeCapabilities capabilities,
            TaskExecution execution,
            ExecutionLease lease,
            ClaimToken token) {

        static LeaseFixture from(
                M3TaskRuntimePersistenceIntegrationTest.Fixture fixture,
                TaskExecution execution,
                ExecutionLease lease,
                ClaimToken token) {
            return new LeaseFixture(
                    fixture.organizationId,
                    fixture.environment,
                    fixture.owner,
                    fixture.capabilities(),
                    execution,
                    lease,
                    token);
        }

        io.crewscope.domain.task.TaskExecutionId executionId() { return execution.id(); }
        ExecutionLeaseId leaseId() { return lease.id(); }
        ExecutionRuntimeId runtimeId() { return lease.runtimeId(); }
        RuntimeWorkerId workerId() { return lease.workerId(); }
        long executionVersion() { return execution.version(); }

        LeaseOwnership ownership() {
            return new LeaseOwnership(
                    execution.id(),
                    execution.attempt(),
                    lease.runtimeId(),
                    lease.workerId(),
                    token.hash(),
                    lease.fencingToken());
        }

        LeaseExecutionCommand executionCommand(long expectedExecutionVersion) {
            return new LeaseExecutionCommand(
                    new LeaseCommandScope(
                            organizationId, environment, lease.id(), ownership()),
                    expectedExecutionVersion);
        }

        LeaseTransitionCommand transitionCommand(
                long expectedExecutionVersion, long expectedLeaseVersion) {
            return new LeaseTransitionCommand(
                    new LeaseCommandScope(
                            organizationId, environment, lease.id(), ownership()),
                    expectedExecutionVersion,
                    expectedLeaseVersion);
        }

        LeaseHeartbeatCommand heartbeat(long expectedLeaseVersion) {
            return new LeaseHeartbeatCommand(
                    new LeaseCommandScope(
                            organizationId, environment, lease.id(), ownership()),
                    expectedLeaseVersion);
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
        JdbcTaskEventRepository.class,
        JdbcDomainEventStore.class,
        JdbcOutboxRepository.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {}
}
