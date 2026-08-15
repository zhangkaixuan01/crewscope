package io.crewscope.infrastructure.persistence.taskruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactContent;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactMutationContext;
import io.crewscope.application.artifact.ArtifactPurgeRequest;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactTombstone;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.application.execution.TaskAgentStateCheckpointCommand;
import io.crewscope.application.execution.TaskAgentStateCheckpointResult;
import io.crewscope.application.execution.TaskAgentStateIdentity;
import io.crewscope.application.execution.TaskAgentStateRecoveryCommand;
import io.crewscope.application.execution.TaskAgentStateSafePoint;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.runtime.ExecutionRuntimeRepository;
import io.crewscope.application.runtime.RuntimeWorkerRepository;
import io.crewscope.application.execution.TaskRuntimeEventCommitWindow;
import io.crewscope.application.execution.TaskRuntimeEventReceipt;
import io.crewscope.application.execution.TaskRuntimeEventReceiptRepository;
import io.crewscope.application.task.AgentInterruptRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.application.task.TaskExecutionQueueRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimToken;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentStateSnapshotStatus;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.LeaseOwnership;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanStepType;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.ProposedPlan;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.RuntimeArtifactKind;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskCredentialIssuance;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskProviderGrantRequest;
import io.crewscope.domain.task.TaskTokenAccessRequest;
import io.crewscope.domain.task.TaskTokenJti;
import io.crewscope.domain.task.TodoStatus;
import io.crewscope.domain.task.TodoSummaryItem;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.agentscope.snapshot.DurableAgentStateSnapshotService;
import io.crewscope.infrastructure.artifact.FilesystemArtifactStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL evidence for M3-D09 mappings, queue locks and conditional ownership writes. */
@SpringBootTest(
        classes = M3TaskRuntimePersistenceIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class M3TaskRuntimePersistenceIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp CREATED = UtcTimestamp.parse("2026-08-14T01:00:00Z");
    private static final UtcTimestamp READY = UtcTimestamp.parse("2026-08-14T01:05:00Z");
    private static final UtcTimestamp CLAIMED = UtcTimestamp.parse("2026-08-14T01:10:00Z");
    private static final UtcTimestamp PREPARING = UtcTimestamp.parse("2026-08-14T01:11:00Z");
    private static final UtcTimestamp RUNNING = UtcTimestamp.parse("2026-08-14T01:12:00Z");
    private static final ClaimToken CLAIM_TOKEN =
            new ClaimToken("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutionRepository executionRepository;
    @Autowired private PolicySnapshotRepository policyRepository;
    @Autowired private SafetyEnforcementOverlayRepository overlayRepository;
    @Autowired private PlanVersionRepository planRepository;
    @Autowired private StepExecutionRepository stepRepository;
    @Autowired private ExecutionRuntimeRepository runtimeRepository;
    @Autowired private RuntimeWorkerRepository workerRepository;
    @Autowired private ExecutionLeaseRepository leaseRepository;
    @Autowired private TaskExecutionQueueRepository queueRepository;
    @Autowired private TaskCredentialGrantRepository credentialGrantRepository;
    @Autowired private TaskAgentRuntimeSessionRepository taskSessionRepository;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private AgentInterruptRepository interruptRepository;
    @Autowired private RuntimeArtifactRepository artifactRepository;
    @Autowired private AgentStateSnapshotRepository snapshotRepository;
    @Autowired private TaskRuntimeEventReceiptRepository runtimeEventReceiptRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @TempDir Path snapshotArtifactRoot;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void roundTripsTaskPlanningJsonbAndRejectsStaleOrCrossScopeReads() {
        Fixture fixture = seedFixture("MAP");
        Task task = taskRepository.create(fixture.task());
        TaskExecution execution = executionRepository.create(fixture.execution(task, 50, CREATED));

        PolicySnapshot policy = policyRepository.create(fixture.policy(task, execution));
        SafetyEnforcementOverlay overlay = overlayRepository.create(
                SafetyEnforcementOverlay.unrestricted(
                        SafetyEnforcementOverlayId.generate(), task, execution,
                        fixture.owner, READY));
        TaskExecution initialized = execution.initializePlanningContext(
                policy, overlay, 0, fixture.owner, READY);
        executionRepository.update(initialized);

        PlanVersion plan = planRepository.create(fixture.plan(task, initialized, policy, overlay));
        TaskExecution selected = initialized.switchCurrentPlan(
                plan, Optional.empty(), 1, fixture.owner, READY);
        executionRepository.update(selected);
        StepExecution step = stepRepository.create(StepExecution.create(
                StepExecutionId.generate(), task, selected, plan, plan.steps().get(0),
                3, fixture.owner, READY));

        Task loadedTask = taskRepository.findById(fixture.organizationId, task.id()).orElseThrow();
        PolicySnapshot loadedPolicy = policyRepository
                .findById(fixture.organizationId, policy.id()).orElseThrow();
        PlanVersion loadedPlan = planRepository
                .findById(fixture.organizationId, plan.id()).orElseThrow();

        assertEquals(task.responsibilitySnapshot().entries(),
                loadedTask.responsibilitySnapshot().entries());
        assertEquals(policy.capabilities(), loadedPolicy.capabilities());
        assertEquals(policy.allowedTools(), loadedPolicy.allowedTools());
        assertEquals(plan.steps(), loadedPlan.steps());
        assertEquals(plan.todoSummary(), loadedPlan.todoSummary());
        assertEquals(step.id(), stepRepository.findById(fixture.organizationId, step.id())
                .orElseThrow().id());
        assertFalse(taskRepository.findById(OrganizationId.generate(), task.id()).isPresent());

        TaskExecution ready = selected.markReady(2, fixture.owner, READY);
        executionRepository.update(ready);
        assertThrows(OptimisticLockConflictException.class,
                () -> executionRepository.update(ready));
    }

    @Test
    void roundTripsRuntimeWorkerJsonbAndStableKeys() {
        Fixture fixture = seedFixture("RUN");
        RuntimeCapabilities capabilities = fixture.capabilities();
        ExecutionRuntime runtime = runtimeRepository.create(ExecutionRuntime.register(
                ExecutionRuntimeId.generate(), fixture.organizationId, fixture.environment,
                "agentscope-java", "AgentScope Java", "2.0.0", capabilities,
                fixture.owner, CREATED));
        RuntimeWorker registered = workerRepository.create(RuntimeWorker.register(
                RuntimeWorkerId.generate(), runtime, "crewscope-worker-01",
                RuntimeProfile.WORKER, capabilities, new RuntimeWorkerCapacity(4, 0),
                fixture.executor, CREATED));
        RuntimeWorker active = workerRepository.update(
                registered.activate(0, fixture.executor, CLAIMED));

        assertEquals(capabilities, runtimeRepository.findByKey(
                fixture.organizationId, fixture.environment, "agentscope-java")
                .orElseThrow().capabilities());
        assertEquals(active.id(), workerRepository.findByStableKey(
                fixture.organizationId, fixture.environment, runtime.id(),
                "crewscope-worker-01").orElseThrow().id());
        assertTrue(runtimeRepository.findByEnvironment(
                OrganizationId.generate(), fixture.environment).isEmpty());
    }

    @Test
    void ordersReadyQueueUsesKeysetIndexAndSkipsRowsLockedByAnotherClaimTransaction()
            throws Exception {
        Fixture fixture = seedFixture("QUE");
        TaskExecution first = persistReady(fixture, 90, CREATED);
        TaskExecution second = persistReady(fixture, 90, READY);
        TaskExecution third = persistReady(fixture, 10, CREATED);
        TaskExecutionQueueRepository.ReadyQuery firstPageQuery = new TaskExecutionQueueRepository.ReadyQuery(
                fixture.organizationId, Optional.of(fixture.teamId), READY,
                Optional.empty(), 2);
        TaskExecutionQueueRepository.ReadyPage firstPage = queueRepository.findReadyPage(firstPageQuery);
        TaskExecutionQueueRepository.ReadyPage secondPage = queueRepository.findReadyPage(
                new TaskExecutionQueueRepository.ReadyQuery(
                        fixture.organizationId, Optional.of(fixture.teamId), READY,
                        firstPage.nextCursor(), 2));

        assertEquals(List.of(first.id(), second.id()),
                firstPage.executions().stream().map(TaskExecution::id).toList());
        assertEquals(List.of(third.id()),
                secondPage.executions().stream().map(TaskExecution::id).toList());

        String plan = String.join("\n", jdbcTemplate.queryForList(
                """
                EXPLAIN (COSTS OFF)
                SELECT id FROM crewscope.task_execution
                WHERE organization_id = ? AND status = 'READY' AND not_before <= ?
                ORDER BY priority DESC, not_before, created_at, id LIMIT 10
                """,
                String.class, fixture.organizationId.value(), READY.toOffsetDateTime()));
        assertTrue(plan.contains("ix_task_execution_ready_queue"), plan);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<TaskExecutionId> lockedFirst = pool.submit(() -> transaction.execute(status -> {
                TaskExecutionId id = queueRepository.lockReadyBatch(
                        new TaskExecutionQueueRepository.ReadyQuery(
                                fixture.organizationId, Optional.of(fixture.teamId), READY,
                                Optional.empty(), 1)).get(0).id();
                firstLocked.countDown();
                await(releaseFirst);
                return id;
            }));
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
            Future<TaskExecutionId> skipped = pool.submit(() -> transaction.execute(status ->
                    queueRepository.lockReadyBatch(
                            new TaskExecutionQueueRepository.ReadyQuery(
                                    fixture.organizationId, Optional.of(fixture.teamId), READY,
                                    Optional.empty(), 1)).get(0).id()));
            TaskExecutionId skippedId = skipped.get(5, TimeUnit.SECONDS);
            releaseFirst.countDown();
            assertNotEquals(lockedFirst.get(5, TimeUnit.SECONDS), skippedId);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void claimsHeartbeatsRollsBackFailedPhaseSwitchAndReleasesWithConditionalOwnership()
            throws Exception {
        Fixture fixture = seedFixture("LSE");
        TaskExecution ready = persistReady(fixture, 50, CREATED);
        RuntimeCapabilities capabilities = fixture.capabilities();
        ExecutionRuntime runtime = runtimeRepository.create(ExecutionRuntime.register(
                ExecutionRuntimeId.generate(), fixture.organizationId, fixture.environment,
                "agentscope-java", "AgentScope Java", "2.0.0", capabilities,
                fixture.owner, CREATED));
        RuntimeWorker registered = workerRepository.create(RuntimeWorker.register(
                RuntimeWorkerId.generate(), runtime, "crewscope-worker-01",
                RuntimeProfile.WORKER, capabilities, new RuntimeWorkerCapacity(4, 0),
                fixture.executor, CREATED));
        RuntimeWorker worker = workerRepository.update(
                registered.activate(0, fixture.executor, CLAIMED));

        TaskExecution claimed = ready.claim(1, fixture.executor, CLAIMED);
        ExecutionLease lease = ExecutionLease.acquire(
                ExecutionLeaseId.generate(), claimed, runtime, worker, capabilities,
                Duration.ofMinutes(2), CLAIM_TOKEN, CLAIMED,
                UtcTimestamp.parse("2026-08-14T01:14:00Z"));
        leaseRepository.acquire(claimed, lease);

        TaskExecution expiringReady = persistReady(fixture, 40, CREATED);
        TaskExecution expiringClaimed = expiringReady.claim(1, fixture.executor, CLAIMED);
        ExecutionLease expiringLease = ExecutionLease.acquire(
                ExecutionLeaseId.generate(), expiringClaimed, runtime, worker, capabilities,
                Duration.ofMinutes(2),
                new ClaimToken("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"), CLAIMED,
                UtcTimestamp.parse("2026-08-14T01:13:00Z"));
        leaseRepository.acquire(expiringClaimed, expiringLease);

        UtcTimestamp sweepTime = UtcTimestamp.parse("2026-08-14T01:14:00Z");
        assertThrows(IllegalTransactionStateException.class, () -> leaseRepository.findExpired(
                fixture.organizationId, fixture.environment, sweepTime, 10));
        assertEquals(List.of(expiringLease.id(), lease.id()), inTransaction(() ->
                leaseRepository.findExpired(
                                fixture.organizationId, fixture.environment, sweepTime, 10)
                        .stream().map(ExecutionLease::id).toList()));
        assertTrue(inTransaction(() -> leaseRepository.findExpired(
                OrganizationId.generate(), fixture.environment, sweepTime, 10)).isEmpty());

        CountDownLatch firstExpiredLocked = new CountDownLatch(1);
        CountDownLatch releaseExpired = new CountDownLatch(1);
        var sweepers = Executors.newFixedThreadPool(2);
        try {
            Future<ExecutionLeaseId> lockedFirst = sweepers.submit(() -> inTransaction(() -> {
                ExecutionLeaseId id = leaseRepository.findExpired(
                        fixture.organizationId, fixture.environment, sweepTime, 1).get(0).id();
                firstExpiredLocked.countDown();
                await(releaseExpired);
                return id;
            }));
            assertTrue(firstExpiredLocked.await(5, TimeUnit.SECONDS));
            Future<ExecutionLeaseId> skipped = sweepers.submit(() -> inTransaction(() ->
                    leaseRepository.findExpired(
                            fixture.organizationId, fixture.environment, sweepTime, 1).get(0).id()));
            ExecutionLeaseId skippedId = skipped.get(5, TimeUnit.SECONDS);
            releaseExpired.countDown();
            assertNotEquals(lockedFirst.get(5, TimeUnit.SECONDS), skippedId);
        } finally {
            releaseExpired.countDown();
            sweepers.shutdownNow();
        }

        TaskExecution recovering = expiringClaimed.beginRecovery(2, fixture.owner, sweepTime);
        ExecutionLease expired = expiringLease.expire(0, sweepTime);
        leaseRepository.release(recovering, expired);
        assertEquals(ExecutionLeaseReleaseReason.EXPIRED, leaseRepository
                .findById(fixture.organizationId, fixture.environment, expiringLease.id())
                .orElseThrow().release().orElseThrow().reason());
        assertEquals(TaskExecutionStatus.RECOVERING, executionRepository
                .findById(fixture.organizationId, expiringClaimed.id()).orElseThrow().status());
        LeaseOwnership ownership = new LeaseOwnership(
                claimed.id(), claimed.attempt(), runtime.id(), worker.id(),
                CLAIM_TOKEN.hash(), FencingToken.initial());
        ExecutionLease heartbeat = lease.heartbeat(
                ownership, 0, PREPARING, UtcTimestamp.parse("2026-08-14T01:15:00Z"));
        leaseRepository.renew(heartbeat);
        TaskExecution preparing = claimed.beginPreparing(2, fixture.executor, PREPARING);
        executionRepository.update(preparing);
        TaskExecution running = preparing.beginRunning(3, fixture.executor, RUNNING);
        ExecutionLease invalidVersion = ExecutionLease.reconstitute(
                heartbeat.id(), heartbeat.organizationId(), heartbeat.environment(),
                heartbeat.taskExecutionId(), heartbeat.attempt(), heartbeat.runtimeId(),
                heartbeat.workerId(), heartbeat.claimTokenHash(), heartbeat.fencingToken(),
                ExecutionLeasePhase.RUN, heartbeat.acquiredAt(), heartbeat.lastHeartbeatAt(),
                heartbeat.expiresAt(), Optional.empty(), 99);

        assertThrows(OptimisticLockConflictException.class,
                () -> leaseRepository.switchPhase(running, invalidVersion));
        assertEquals(TaskExecutionStatus.PREPARING,
                executionRepository.findById(fixture.organizationId, ready.id())
                        .orElseThrow().status());

        ExecutionLease runLease = heartbeat.beginRun(
                running, ownership, 1, RUNNING,
                UtcTimestamp.parse("2026-08-14T01:18:00Z"));
        leaseRepository.switchPhase(running, runLease);
        TaskExecution completed = running.complete(4, fixture.executor,
                UtcTimestamp.parse("2026-08-14T01:16:00Z"));
        ExecutionLease released = runLease.release(
                completed, ownership, ExecutionLeaseReleaseReason.COMPLETED, 2,
                UtcTimestamp.parse("2026-08-14T01:16:00Z"));
        leaseRepository.release(completed, released);

        assertEquals(TaskExecutionStatus.COMPLETED,
                executionRepository.findById(fixture.organizationId, ready.id())
                        .orElseThrow().status());
        assertTrue(leaseRepository.findActiveByTaskExecution(
                fixture.organizationId, ready.id()).isEmpty());
        assertThrows(RuntimeException.class, () -> leaseRepository.renew(heartbeat));
    }

    @Test
    void roundTripsUsesRotatesAndExpiresTaskCredentialGrantScope() {
        Fixture fixture = seedFixture("GRT");
        fixture.seedProviderBinding(jdbcTemplate);
        ProviderBinding providerBinding = fixture.providerBinding();
        Task task = taskRepository.create(fixture.task());
        TaskExecution created = executionRepository.create(fixture.execution(task, 50, CREATED));
        PolicySnapshot policy = policyRepository.create(
                fixture.policy(task, created, Set.of(providerBinding.id())));
        SafetyEnforcementOverlay overlay = overlayRepository.create(
                SafetyEnforcementOverlay.unrestricted(
                        SafetyEnforcementOverlayId.generate(), task, created,
                        fixture.owner, READY));
        TaskExecution initialized = executionRepository.update(
                created.initializePlanningContext(policy, overlay, 0, fixture.owner, READY));
        TaskExecution ready = executionRepository.update(
                initialized.markReady(1, fixture.owner, READY));
        TaskExecution claimed = ready.claim(2, fixture.executor, CLAIMED);
        RuntimeCapabilities capabilities = fixture.capabilities();
        ExecutionRuntime runtime = runtimeRepository.create(ExecutionRuntime.register(
                ExecutionRuntimeId.generate(), fixture.organizationId, fixture.environment,
                "agentscope-java", "AgentScope Java", "2.0.0", capabilities,
                fixture.owner, CREATED));
        RuntimeWorker worker = workerRepository.update(workerRepository.create(
                        RuntimeWorker.register(
                                RuntimeWorkerId.generate(), runtime, "grant-worker",
                                RuntimeProfile.WORKER, capabilities,
                                new RuntimeWorkerCapacity(4, 0), fixture.executor, CREATED))
                .activate(0, fixture.executor, CLAIMED));
        ExecutionLease lease = ExecutionLease.acquire(
                ExecutionLeaseId.generate(), claimed, runtime, worker, capabilities,
                Duration.ofMinutes(2), CLAIM_TOKEN, CLAIMED,
                UtcTimestamp.parse("2026-08-14T01:14:00Z"));
        leaseRepository.acquire(claimed, lease);

        UtcTimestamp issuedAt = UtcTimestamp.parse("2026-08-14T01:10:10Z");
        UtcTimestamp expiresAt = UtcTimestamp.parse("2026-08-14T01:13:00Z");
        TaskCredentialIssuance issuance = TaskCredentialGrant.issue(
                TaskCredentialGrantId.generate(), claimed, lease, policy, overlay,
                Set.of("repository.read"), List.of(fixture.providerRequest(providerBinding)),
                new TaskTokenJti("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"),
                expiresAt, fixture.executor, issuedAt);
        TaskCredentialGrant persisted = credentialGrantRepository.create(issuance.grant());
        TaskCredentialGrant used = persisted.use(
                issuance.claims(), lease, TaskTokenAccessRequest.tool("repository.read"),
                0, UtcTimestamp.parse("2026-08-14T01:11:00Z"));
        credentialGrantRepository.recordUse(used);

        assertEquals(Set.of("repository.read"), credentialGrantRepository
                .findByJtiHash(fixture.organizationId, persisted.jtiHash())
                .orElseThrow().scope().allowedTools());
        assertEquals(Set.of(providerBinding.id()), credentialGrantRepository
                .findByJtiHash(fixture.organizationId, persisted.jtiHash())
                .orElseThrow().scope().providerAuthorizations().stream()
                .map(authorization -> authorization.bindingId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, credentialGrantRepository
                .findActiveByTaskExecution(
                        fixture.organizationId, fixture.environment, claimed.id())
                .orElseThrow().useCount());
        assertThrows(OptimisticLockConflictException.class,
                () -> credentialGrantRepository.recordUse(used));

        TaskCredentialGrant revoked = used.revoke(
                1, fixture.executor, "ROTATED",
                UtcTimestamp.parse("2026-08-14T01:12:00Z"));
        TaskCredentialIssuance replacement = TaskCredentialGrant.issue(
                TaskCredentialGrantId.generate(), claimed, lease, policy, overlay,
                Set.of("repository.read"), List.of(fixture.providerRequest(providerBinding)),
                new TaskTokenJti("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"),
                UtcTimestamp.parse("2026-08-14T01:13:30Z"), fixture.executor,
                UtcTimestamp.parse("2026-08-14T01:12:10Z"));
        credentialGrantRepository.rotate(revoked, replacement.grant());

        assertEquals(replacement.grant().id(), credentialGrantRepository
                .findActiveByTaskExecution(
                        fixture.organizationId, fixture.environment, claimed.id())
                .orElseThrow().id());
        assertEquals(List.of(replacement.grant().id()), inTransaction(() ->
                credentialGrantRepository.findExpired(
                                fixture.organizationId, fixture.environment,
                                UtcTimestamp.parse("2026-08-14T01:14:00Z"), 10)
                        .stream().map(TaskCredentialGrant::id).toList()));
        assertTrue(credentialGrantRepository.findByJtiHash(
                OrganizationId.generate(), replacement.grant().jtiHash()).isEmpty());
    }

    @Test
    void persistsTaskAgentRunInterruptArtifactAndSnapshotRecoveryGraph() {
        Fixture fixture = seedFixture("AGT");
        PlanningGraph graph = persistPlanningGraph(fixture);
        AgentProfile profile = fixture.profile();
        TaskAgentRuntimeSession candidate = TaskAgentRuntimeSession.initializeStep(
                graph.task(), graph.execution(), graph.step(), profile,
                fixture.executor, RUNNING);
        TaskAgentRuntimeSession session = taskSessionRepository.initializeIfAbsent(candidate);
        TaskAgentRuntimeSession retried = taskSessionRepository.initializeIfAbsent(candidate);

        assertEquals(session.id(), retried.id());
        TaskAgentRuntimeSession collision = TaskAgentRuntimeSession.reconstitute(
                candidate.id(), candidate.scope(), TaskId.generate(), candidate.executionId(),
                candidate.stepExecutionId(), candidate.purpose(), candidate.agentPrincipalId(),
                candidate.agentProfileId(), candidate.agentProfileVersion(), candidate.agentScopeKey(),
                candidate.stateReference(), candidate.status(), candidate.version(), candidate.audit());
        assertThrows(DomainValidationException.class,
                () -> taskSessionRepository.initializeIfAbsent(collision));
        assertEquals(List.of(session.id()), taskSessionRepository
                .findByStep(fixture.organizationId, graph.step().id())
                .stream().map(TaskAgentRuntimeSession::id).toList());

        AgentRun run = agentRunRepository.createNext(AgentRun.start(
                AgentRunId.generate(), session, 1, fixture.executor,
                UtcTimestamp.parse("2026-08-14T01:12:30Z")));
        AgentInterrupt pending = interruptRepository.createPending(AgentInterrupt.open(
                AgentInterruptId.generate(), run, AgentInterruptKind.CLARIFICATION,
                RuntimeContentHash.sha256("opaque-interrupt-token"), fixture.executor,
                UtcTimestamp.parse("2026-08-14T01:13:00Z")));
        AgentRun interrupted = agentRunRepository.update(run.interrupt(
                pending, 0, fixture.executor,
                UtcTimestamp.parse("2026-08-14T01:13:10Z")));
        UUID resumeRequestId = UUID.randomUUID();
        AgentInterrupt resolved = interruptRepository.update(pending.resolve(
                resumeRequestId, RuntimeContentHash.sha256("approved-answer"), 0,
                fixture.owner, UtcTimestamp.parse("2026-08-14T01:13:20Z")));
        AgentRun resumed = agentRunRepository.update(interrupted.resume(
                resolved, 1, fixture.owner,
                UtcTimestamp.parse("2026-08-14T01:13:30Z")));

        assertEquals(AgentRunStatus.RUNNING, agentRunRepository
                .findActiveBySession(fixture.organizationId, session.id())
                .orElseThrow().status());
        assertEquals(resolved.id(), interruptRepository
                .findByResumeRequestId(fixture.organizationId, resumeRequestId)
                .orElseThrow().id());
        assertEquals(2, agentRunRepository.findById(fixture.organizationId, run.id())
                .orElseThrow().segments().size());

        RuntimeArtifact firstArtifact = artifactRepository.create(RuntimeArtifact.register(
                RuntimeArtifactId.generate(), ArtifactId.generate(), resumed,
                RuntimeArtifactKind.AGENT_STATE_SNAPSHOT, AgentStateSnapshot.CONTENT_TYPE,
                1_024, RuntimeContentHash.sha256("snapshot-one"), Optional.empty(),
                fixture.executor, UtcTimestamp.parse("2026-08-14T01:14:00Z")));
        AgentStateSnapshot first = snapshotRepository.publish(Optional.empty(),
                AgentStateSnapshot.capture(
                        AgentStateSnapshotId.generate(), session, resumed, firstArtifact,
                        "step_agent", 1, 7, fixture.executor,
                        UtcTimestamp.parse("2026-08-14T01:14:10Z")));
        RuntimeArtifact secondArtifact = artifactRepository.create(RuntimeArtifact.register(
                RuntimeArtifactId.generate(), ArtifactId.generate(), resumed,
                RuntimeArtifactKind.AGENT_STATE_SNAPSHOT, AgentStateSnapshot.CONTENT_TYPE,
                2_048, RuntimeContentHash.sha256("snapshot-two"), Optional.empty(),
                fixture.executor, UtcTimestamp.parse("2026-08-14T01:14:20Z")));
        AgentStateSnapshot second = AgentStateSnapshot.capture(
                AgentStateSnapshotId.generate(), session, resumed, secondArtifact,
                "step_agent", 2, 9, fixture.executor,
                UtcTimestamp.parse("2026-08-14T01:14:30Z"));
        AgentStateSnapshot superseded = first.supersedeBy(
                second, 0, fixture.executor,
                UtcTimestamp.parse("2026-08-14T01:14:30Z"));
        snapshotRepository.publish(Optional.of(superseded), second);

        assertEquals(second.id(), snapshotRepository
                .findCurrentBySession(fixture.organizationId, session.id())
                .orElseThrow().id());
        assertEquals(List.of(second.id(), first.id()), snapshotRepository
                .findRecoveryCandidates(fixture.organizationId, resumed.id(), 10)
                .stream().map(AgentStateSnapshot::id).toList());
        assertEquals(AgentStateSnapshotStatus.SUPERSEDED, snapshotRepository
                .findById(fixture.organizationId, first.id()).orElseThrow().status());
        assertEquals(List.of(firstArtifact.id(), secondArtifact.id()), artifactRepository
                .findByAgentRun(fixture.organizationId, resumed.id())
                .stream().map(RuntimeArtifact::id).toList());
        assertTrue(agentRunRepository.findById(OrganizationId.generate(), resumed.id()).isEmpty());
        assertThrows(OptimisticLockConflictException.class,
                () -> agentRunRepository.update(resumed));
    }

    @Test
    void serializesExactAgentRunEventReceiptsAndRollsBackIncompleteCommit() {
        Fixture fixture = seedFixture("EVT");
        PlanningGraph graph = persistPlanningGraph(fixture);
        TaskAgentRuntimeSession session = taskSessionRepository.initializeIfAbsent(
                TaskAgentRuntimeSession.initializeStep(
                        graph.task(), graph.execution(), graph.step(), fixture.profile(),
                        fixture.executor, RUNNING));
        AgentRun run = agentRunRepository.createNext(AgentRun.start(
                AgentRunId.generate(), session, 1, fixture.executor, RUNNING));

        assertThrows(IllegalTransactionStateException.class, () ->
                runtimeEventReceiptRepository.lockCommitWindow(
                        fixture.organizationId, run.id(), 1, 1));

        UUID firstEventId = insertRuntimeDomainEvent(fixture, run, 1);
        TaskRuntimeEventReceipt first = new TaskRuntimeEventReceipt(
                fixture.organizationId,
                run.id(),
                1,
                1,
                RuntimeContentHash.sha256("complete-event-one"),
                "STARTED",
                firstEventId,
                RUNNING,
                RUNNING);
        TaskRuntimeEventCommitWindow before = inTransaction(() ->
                runtimeEventReceiptRepository.lockCommitWindow(
                        fixture.organizationId, run.id(), 1, 1));
        assertEquals(1, before.nextSequence());
        assertTrue(before.existingReceipt().isEmpty());
        inTransaction(() -> runtimeEventReceiptRepository.create(first));
        TaskRuntimeEventCommitWindow replay = inTransaction(() ->
                runtimeEventReceiptRepository.lockCommitWindow(
                        fixture.organizationId, run.id(), 1, 1));
        assertEquals(2, replay.nextSequence());
        assertEquals(first.eventHash(), replay.existingReceipt().orElseThrow().eventHash());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID rolledBackEventId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            insertRuntimeDomainEvent(fixture, run, 2, rolledBackEventId);
            runtimeEventReceiptRepository.create(new TaskRuntimeEventReceipt(
                    fixture.organizationId,
                    run.id(),
                    1,
                    2,
                    RuntimeContentHash.sha256("rolled-back-terminal"),
                    "COMPLETED",
                    rolledBackEventId,
                    RUNNING,
                    RUNNING));
            status.setRollbackOnly();
        });
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.agent_run_event_receipt WHERE domain_event_id = ?",
                Integer.class,
                rolledBackEventId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.domain_event WHERE event_id = ?",
                Integer.class,
                rolledBackEventId));
    }

    @Test
    void publishesAndRecoversDurableAgentStateWithCorruptLatestFallback() throws Exception {
        Fixture fixture = seedFixture("SNP");
        PlanningGraph graph = persistPlanningGraph(fixture);
        TaskAgentRuntimeSession session = taskSessionRepository.initializeIfAbsent(
                TaskAgentRuntimeSession.initializeStep(
                        graph.task(), graph.execution(), graph.step(), fixture.profile(),
                        fixture.executor, RUNNING));
        AgentRun run = agentRunRepository.createNext(AgentRun.start(
                AgentRunId.generate(), session, 1, fixture.executor, RUNNING));
        TaskExecutionRuntimeFacts facts = runtimeFacts(graph, session, run);
        TaskAgentStateIdentity identity = new TaskAgentStateIdentity(
                graph.execution().id().value(),
                run.id().value(),
                stableAgentId(session),
                stableAgentId(session),
                Long.toString(session.agentProfileVersion()),
                session.agentScopeKey().userId(),
                session.agentScopeKey().sessionId());
        PrincipalRepository principals = mock(PrincipalRepository.class);
        when(principals.findById(fixture.organizationId, fixture.executor.id()))
                .thenReturn(Optional.of(fixture.executor));
        ArtifactStore store = new FilesystemArtifactStore(
                snapshotArtifactRoot,
                new ObjectMapper(),
                Clock.fixed(RUNNING.value(), ZoneOffset.UTC));
        DurableAgentStateSnapshotService service = new DurableAgentStateSnapshotService(
                store,
                agentRunRepository,
                taskSessionRepository,
                artifactRepository,
                snapshotRepository,
                runtimeEventReceiptRepository,
                activeLeaseRepository(facts),
                principals,
                transactionExecutor(),
                () -> RUNNING);

        commitRuntimeReceipt(fixture, run, 1);
        var first = service.checkpoint(checkpointCommand(
                facts, identity, 1, state(identity, "checkpoint-one")));
        commitRuntimeReceipt(fixture, run, 2);
        var second = service.checkpoint(checkpointCommand(
                facts, identity, 2, state(identity, "checkpoint-two")));

        assertEquals(1, first.snapshotSequence());
        assertEquals(2, second.snapshotSequence());
        assertEquals(AgentStateSnapshotStatus.SUPERSEDED, snapshotRepository
                .findById(fixture.organizationId, first.snapshotId()).orElseThrow().status());
        RuntimeArtifact latestArtifact = artifactRepository.findById(
                        fixture.organizationId, second.runtimeArtifactId())
                .orElseThrow();
        ArtifactAccessContext access = new ArtifactAccessContext(
                fixture.organizationId,
                fixture.executor.id(),
                Set.of(fixture.teamId),
                Set.of(fixture.workspaceId));
        Path latestBlob = Path.of(store.head(latestArtifact.artifactId(), access)
                .orElseThrow().storageUri());
        Files.writeString(latestBlob, "corrupt", StandardCharsets.UTF_8);

        var recovered = service.recover(new TaskAgentStateRecoveryCommand(facts, identity, 10));

        assertEquals(first.snapshotId(), recovered.snapshotId());
        assertEquals(1, recovered.checkpointSequence());
        assertTrue(recovered.continuityGap().isPresent());
        assertEquals("checkpoint-one", AgentState.fromJsonString(recovered.agentStateJson())
                .getContext().get(0).getTextContent());
        assertEquals(AgentStateSnapshotStatus.INVALID, snapshotRepository
                .findById(fixture.organizationId, second.snapshotId()).orElseThrow().status());
        assertTrue(store.head(latestArtifact.artifactId(), access)
                .orElseThrow().tombstone().isPresent());
    }

    @Test
    void refusesSnapshotBeforeTheReferencedRuntimeReceiptCommits() {
        ArtifactStore store = new FilesystemArtifactStore(
                snapshotArtifactRoot.resolve("receipt-gate"),
                new ObjectMapper(),
                Clock.fixed(RUNNING.value(), ZoneOffset.UTC));
        DurableSnapshotFixture snapshot = durableSnapshotFixture("SNG", store);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> snapshot.service().checkpoint(checkpointCommand(
                        snapshot.facts(),
                        snapshot.identity(),
                        1,
                        state(snapshot.identity(), "must-not-publish"))));

        assertTrue(failure.getMessage().contains("event receipt"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.agent_state_snapshot", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.runtime_artifact", Integer.class));
    }

    @Test
    void concurrentWritersPublishOneCurrentSnapshotAndTombstoneTheLoser() throws Exception {
        ArtifactStore filesystem = new FilesystemArtifactStore(
                snapshotArtifactRoot.resolve("concurrent-writers"),
                new ObjectMapper(),
                Clock.fixed(RUNNING.value(), ZoneOffset.UTC));
        BarrierArtifactStore store = new BarrierArtifactStore(filesystem, 2);
        DurableSnapshotFixture snapshot = durableSnapshotFixture("SCW", store);
        commitRuntimeReceipt(snapshot.fixture(), snapshot.run(), 1);

        ExecutorService writers = Executors.newFixedThreadPool(2);
        List<Future<TaskAgentStateCheckpointResult>> attempts;
        try {
            attempts = List.of(
                    writers.submit(() -> snapshot.service().checkpoint(checkpointCommand(
                            snapshot.facts(),
                            snapshot.identity(),
                            1,
                            state(snapshot.identity(), "writer-one")))),
                    writers.submit(() -> snapshot.service().checkpoint(checkpointCommand(
                            snapshot.facts(),
                            snapshot.identity(),
                            1,
                            state(snapshot.identity(), "writer-two")))));

            int successes = 0;
            Throwable rejected = null;
            for (Future<TaskAgentStateCheckpointResult> attempt : attempts) {
                try {
                    TaskAgentStateCheckpointResult committed = attempt.get(20, TimeUnit.SECONDS);
                    assertEquals(1, committed.snapshotSequence());
                    assertEquals(1, committed.checkpointSequence());
                    successes++;
                } catch (ExecutionException failure) {
                    rejected = failure.getCause();
                }
            }
            assertEquals(1, successes);
            assertInstanceOf(RuntimeException.class, rejected);
        } finally {
            writers.shutdownNow();
        }

        AgentStateSnapshot current = snapshotRepository.findCurrentBySession(
                        snapshot.fixture().organizationId, snapshot.session().id())
                .orElseThrow();
        assertEquals(1, current.snapshotSequence());
        assertEquals(1, current.checkpointSequence());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.agent_state_snapshot", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crewscope.runtime_artifact", Integer.class));

        ArtifactAccessContext access = snapshotAccess(snapshot.fixture());
        List<ArtifactDescriptor> artifacts = store.publishedArtifactIds().stream()
                .map(id -> store.head(id, access).orElseThrow())
                .toList();
        assertEquals(2, artifacts.size());
        assertEquals(1, artifacts.stream().filter(value -> value.tombstone().isEmpty()).count());
        ArtifactTombstone rejected = artifacts.stream()
                .map(ArtifactDescriptor::tombstone)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow();
        assertEquals(ArtifactTombstoneReason.PUBLICATION_ABORTED, rejected.reason());
    }

    private DurableSnapshotFixture durableSnapshotFixture(String suffix, ArtifactStore store) {
        Fixture fixture = seedFixture(suffix);
        PlanningGraph graph = persistPlanningGraph(fixture);
        TaskAgentRuntimeSession session = taskSessionRepository.initializeIfAbsent(
                TaskAgentRuntimeSession.initializeStep(
                        graph.task(), graph.execution(), graph.step(), fixture.profile(),
                        fixture.executor, RUNNING));
        AgentRun run = agentRunRepository.createNext(AgentRun.start(
                AgentRunId.generate(), session, 1, fixture.executor, RUNNING));
        TaskExecutionRuntimeFacts facts = runtimeFacts(graph, session, run);
        TaskAgentStateIdentity identity = new TaskAgentStateIdentity(
                graph.execution().id().value(),
                run.id().value(),
                stableAgentId(session),
                stableAgentId(session),
                Long.toString(session.agentProfileVersion()),
                session.agentScopeKey().userId(),
                session.agentScopeKey().sessionId());
        PrincipalRepository principals = mock(PrincipalRepository.class);
        when(principals.findById(fixture.organizationId, fixture.executor.id()))
                .thenReturn(Optional.of(fixture.executor));
        DurableAgentStateSnapshotService service = new DurableAgentStateSnapshotService(
                store,
                agentRunRepository,
                taskSessionRepository,
                artifactRepository,
                snapshotRepository,
                runtimeEventReceiptRepository,
                activeLeaseRepository(facts),
                principals,
                transactionExecutor(),
                () -> RUNNING);
        return new DurableSnapshotFixture(fixture, session, run, facts, identity, service);
    }

    private static ArtifactAccessContext snapshotAccess(Fixture fixture) {
        return new ArtifactAccessContext(
                fixture.organizationId,
                fixture.executor.id(),
                Set.of(fixture.teamId),
                Set.of(fixture.workspaceId));
    }

    private static String stableAgentId(TaskAgentRuntimeSession session) {
        return TaskAgentStateIdentity.stableAgentId(
                session.agentProfileId(), session.agentProfileVersion());
    }

    private void commitRuntimeReceipt(Fixture fixture, AgentRun run, long sequence) {
        UUID eventId = insertRuntimeDomainEvent(fixture, run, sequence);
        inTransaction(() -> runtimeEventReceiptRepository.create(new TaskRuntimeEventReceipt(
                fixture.organizationId,
                run.id(),
                1,
                sequence,
                RuntimeContentHash.sha256("snapshot-event-" + sequence),
                "PROGRESS",
                eventId,
                RUNNING,
                RUNNING)));
    }

    private static TaskExecutionRuntimeFacts runtimeFacts(
            PlanningGraph graph, TaskAgentRuntimeSession session, AgentRun run) {
        TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        ExecutionLease lease = mock(ExecutionLease.class);
        RuntimeEnvironment environment = new RuntimeEnvironment("test");
        when(lease.id()).thenReturn(ExecutionLeaseId.generate());
        when(lease.environment()).thenReturn(environment);
        when(lease.taskExecutionId()).thenReturn(graph.execution().id());
        when(lease.attempt()).thenReturn(graph.execution().attempt());
        when(lease.runtimeId()).thenReturn(ExecutionRuntimeId.generate());
        when(lease.workerId()).thenReturn(RuntimeWorkerId.generate());
        when(lease.claimTokenHash()).thenReturn(
                new ClaimToken("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq").hash());
        when(lease.fencingToken()).thenReturn(FencingToken.initial());
        when(lease.owns(any(LeaseOwnership.class), any(UtcTimestamp.class))).thenReturn(true);
        when(facts.task()).thenReturn(graph.task());
        when(facts.execution()).thenReturn(graph.execution());
        when(facts.lease()).thenReturn(lease);
        when(facts.stepExecution()).thenReturn(Optional.of(graph.step()));
        when(facts.runtimeSession()).thenReturn(session);
        when(facts.agentRun()).thenReturn(run);
        return facts;
    }

    private static ExecutionLeaseRepository activeLeaseRepository(
            TaskExecutionRuntimeFacts facts) {
        ExecutionLeaseRepository repository = mock(ExecutionLeaseRepository.class);
        ExecutionLease lease = facts.lease();
        when(repository.findByIdForUpdate(
                        facts.task().scope().organizationId(),
                        lease.environment(),
                        lease.id()))
                .thenReturn(Optional.of(lease));
        return repository;
    }

    private static TaskAgentStateCheckpointCommand checkpointCommand(
            TaskExecutionRuntimeFacts facts,
            TaskAgentStateIdentity identity,
            long eventSequence,
            AgentState state) {
        return new TaskAgentStateCheckpointCommand(
                facts,
                identity,
                1,
                eventSequence,
                TaskAgentStateSafePoint.PERIODIC,
                state.toJson(),
                Optional.of(Duration.ofDays(30)));
    }

    private static AgentState state(TaskAgentStateIdentity identity, String text) {
        return AgentState.builder()
                .userId(identity.userId())
                .sessionId(identity.sessionId())
                .addMessage(new UserMessage(text))
                .build();
    }

    private record DurableSnapshotFixture(
            Fixture fixture,
            TaskAgentRuntimeSession session,
            AgentRun run,
            TaskExecutionRuntimeFacts facts,
            TaskAgentStateIdentity identity,
            DurableAgentStateSnapshotService service) {}

    /** Makes both immutable Artifact writes visible before either metadata transaction continues. */
    private static final class BarrierArtifactStore implements ArtifactStore {

        private final ArtifactStore delegate;
        private final CyclicBarrier publicationBarrier;
        private final ConcurrentLinkedQueue<ArtifactId> publishedArtifactIds =
                new ConcurrentLinkedQueue<>();

        private BarrierArtifactStore(ArtifactStore delegate, int writers) {
            this.delegate = delegate;
            this.publicationBarrier = new CyclicBarrier(writers);
        }

        @Override
        public ArtifactDescriptor put(ArtifactWriteRequest request, java.io.InputStream content) {
            ArtifactDescriptor descriptor = delegate.put(request, content);
            publishedArtifactIds.add(descriptor.artifactId());
            try {
                publicationBarrier.await(10, TimeUnit.SECONDS);
                return descriptor;
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Concurrent snapshot Writers did not reach the Artifact boundary", exception);
            }
        }

        @Override
        public Optional<ArtifactDescriptor> head(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            return delegate.head(artifactId, accessContext);
        }

        @Override
        public Optional<ArtifactContent> get(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            return delegate.get(artifactId, accessContext);
        }

        @Override
        public Optional<ArtifactTombstone> tombstone(
                ArtifactId artifactId,
                ArtifactMutationContext mutationContext,
                ArtifactTombstoneReason reason,
                Optional<String> detail) {
            return delegate.tombstone(artifactId, mutationContext, reason, detail);
        }

        @Override
        public List<ArtifactId> purgeTombstoned(ArtifactPurgeRequest request) {
            return delegate.purgeTombstoned(request);
        }

        private List<ArtifactId> publishedArtifactIds() {
            return List.copyOf(publishedArtifactIds);
        }
    }

    private TransactionExecutor transactionExecutor() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return new TransactionTemplate(transactionManager)
                        .execute(status -> operation.get());
            }
        };
    }

    private UUID insertRuntimeDomainEvent(Fixture fixture, AgentRun run, long sequence) {
        UUID eventId = UUID.randomUUID();
        insertRuntimeDomainEvent(fixture, run, sequence, eventId);
        return eventId;
    }

    private void insertRuntimeDomainEvent(
            Fixture fixture, AgentRun run, long sequence, UUID eventId) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.domain_event (
                    event_id, event_type, schema_version,
                    organization_id, team_id, workspace_id,
                    subject_type, subject_id, aggregate_version,
                    actor_type, actor_id, correlation_id,
                    occurred_at, payload
                ) VALUES (
                    ?, 'AGENT_RUN_EVENT_RECORDED', '1',
                    ?, ?, ?,
                    'AGENT_RUN', ?, ?,
                    'TEAM_AGENT', ?, ?,
                    ?, '{}'::jsonb
                )
                """,
                eventId,
                fixture.organizationId.value(),
                fixture.teamId.value(),
                fixture.workspaceId.value(),
                run.id().value(),
                sequence,
                fixture.executor.id().value(),
                UUID.randomUUID(),
                RUNNING.toOffsetDateTime());
    }

    /** Persists the minimum closed planning graph required by Task-side Agent sessions. */
    private PlanningGraph persistPlanningGraph(Fixture fixture) {
        Task task = taskRepository.create(fixture.task());
        TaskExecution created = executionRepository.create(fixture.execution(task, 50, CREATED));
        PolicySnapshot policy = policyRepository.create(fixture.policy(task, created));
        SafetyEnforcementOverlay overlay = overlayRepository.create(
                SafetyEnforcementOverlay.unrestricted(
                        SafetyEnforcementOverlayId.generate(), task, created,
                        fixture.owner, READY));
        TaskExecution initialized = executionRepository.update(
                created.initializePlanningContext(policy, overlay, 0, fixture.owner, READY));
        PlanVersion plan = planRepository.create(fixture.plan(task, initialized, policy, overlay));
        TaskExecution selected = executionRepository.update(initialized.switchCurrentPlan(
                plan, Optional.empty(), 1, fixture.owner, READY));
        StepExecution step = stepRepository.create(StepExecution.create(
                StepExecutionId.generate(), task, selected, plan, plan.steps().get(0),
                3, fixture.owner, READY));
        return new PlanningGraph(task, selected, step);
    }

    private record PlanningGraph(Task task, TaskExecution execution, StepExecution step) {}

    private TaskExecution persistReady(Fixture fixture, int priority, UtcTimestamp notBefore) {
        Task task = taskRepository.create(fixture.task());
        TaskExecution created = executionRepository.create(
                fixture.execution(task, priority, notBefore));
        return executionRepository.update(created.markReady(0, fixture.owner, READY));
    }

    private Fixture seedFixture(String key) {
        Fixture fixture = new Fixture(key);
        fixture.seed(jdbcTemplate);
        return fixture;
    }

    /** Executes lock-returning Repository calls inside their required surrounding transaction. */
    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent lock test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    static final class Fixture {
        final String key;
        final OrganizationId organizationId = OrganizationId.generate();
        final TeamId teamId = TeamId.generate();
        final WorkspaceId workspaceId = WorkspaceId.generate();
        final WorkProjectId projectId = WorkProjectId.generate();
        final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, workspaceId, projectId);
        final TeamMemberId ownerMemberId = TeamMemberId.generate();
        final Principal owner = Principal.create(
                PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER, Optional.empty(), "Owner", Optional.empty(),
                PrincipalVisibility.TEAM, CREATED);
        final Principal executor = Principal.create(
                PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
                PrincipalType.TEAM_AGENT, Optional.of(owner.id()), "Executor", Optional.empty(),
                PrincipalVisibility.TEAM, CREATED);
        final AgentProfileId profileId = AgentProfileId.generate();
        final ProviderDefinitionId providerDefinitionId = ProviderDefinitionId.generate();
        final ProviderImplementationId providerImplementationId = ProviderImplementationId.generate();
        final ProviderBindingId providerBindingId = ProviderBindingId.generate();
        final WorkItem workItem;
        final ResponsibilityAssignment ownerAssignment;
        final ResponsibilityAssignment executorAssignment;
        final RuntimeEnvironment environment = new RuntimeEnvironment("production");

        Fixture(String key) {
            this.key = key;
            this.workItem = WorkItem.reconstitute(
                    WorkItemId.generate(), scope, new WorkItemKey(key + "-1"), "Source task",
                    WorkItemStatus.READY, 0, AuditMetadata.createdBy(owner.id(), CREATED));
            this.ownerAssignment = assignment(
                    ResponsibilityRole.OWNER, owner, Optional.of(ownerMemberId));
            this.executorAssignment = assignment(
                    ResponsibilityRole.EXECUTOR, executor, Optional.empty());
        }

        Task task() {
            TaskResponsibilitySnapshot snapshot = TaskResponsibilitySnapshot.capture(
                    workItem, List.of(ownerAssignment, executorAssignment), CREATED);
            return Task.create(TaskId.generate(), workItem, TaskSource.fromWorkItem(workItem),
                    snapshot, owner, CREATED);
        }

        TaskExecution execution(Task task, int priority, UtcTimestamp notBefore) {
            return TaskExecution.firstAttempt(
                    TaskExecutionId.generate(), task, 3, new TaskExecutionPriority(priority),
                    notBefore, owner, CREATED);
        }

        PolicySnapshot policy(Task task, TaskExecution execution) {
            return policy(task, execution, Set.of());
        }

        PolicySnapshot policy(
                Task task, TaskExecution execution, Set<ProviderBindingId> providerBindingIds) {
            return PolicySnapshot.initial(
                    PolicySnapshotId.generate(), task, execution, executor,
                    new PolicyPackReference(PolicyPackId.generate(), 1), profileId, 0,
                    Set.of(ExecutionCapability.PLAN, ExecutionCapability.STRUCTURED_OUTPUT),
                    Set.of("repository.read", "validation.run"), providerBindingIds,
                    new PolicyBudget(10_000, 20, 50, 3_600), owner, READY);
        }

        PlanVersion plan(
                Task task,
                TaskExecution initialized,
                PolicySnapshot policy,
                SafetyEnforcementOverlay overlay) {
            ProposedPlan proposed = ProposedPlan.of(
                    "# Plan\n\nAnalyze and validate.",
                    List.of(
                            new PlanStep("analyze", 1, "Analyze", PlanStepType.ANALYSIS,
                                    Set.of(), Set.of(ExecutionCapability.PLAN),
                                    Set.of("repository.read"), true),
                            new PlanStep("validate", 2, "Validate", PlanStepType.VALIDATION,
                                    Set.of("analyze"),
                                    Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                                    Set.of("validation.run"), true)));
            return PlanVersion.publishInitial(
                    PlanVersionId.generate(), task, initialized, proposed,
                    List.of(new TodoSummaryItem(
                            "Analyze", TodoStatus.IN_PROGRESS, Optional.of("high"),
                            Optional.of("analyze"))),
                    policy, overlay, owner, READY);
        }

        RuntimeCapabilities capabilities() {
            return RuntimeCapabilities.of(
                    Set.of(RuntimeCapability.CONVERSATION, RuntimeCapability.PLAN),
                    Set.of("java"), Set.of("maven"));
        }

        AgentProfile profile() {
            return AgentProfile.reconstitute(
                    profileId, WorkspaceScope.team(organizationId, teamId), workspaceId,
                    executor.id(), Optional.empty(), AgentProfileType.TEAM, false,
                    AgentProfileStatus.ACTIVE, 0,
                    AuditMetadata.createdBy(owner.id(), CREATED));
        }

        ProviderBinding providerBinding() {
            ProviderBindingTarget target = new ProviderBindingTarget(
                    organizationId, teamId, workspaceId,
                    ProviderBindingTargetType.WORK_PROJECT, Optional.of(projectId));
            return ProviderBinding.reconstitute(
                    providerBindingId, organizationId, target,
                    ProviderOwner.organization(organizationId), providerDefinitionId, 0,
                    ProviderType.SOURCE_CODE, providerImplementationId, 0,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(),
                    new ProviderAccessScope(
                            ProviderCapabilities.of("repository.read"),
                            ProviderResourceScope.of("repository:crewscope")),
                    false, ProviderRegistrationStatus.ACTIVE, 0,
                    AuditMetadata.createdBy(owner.id(), CREATED));
        }

        TaskProviderGrantRequest providerRequest(ProviderBinding binding) {
            return new TaskProviderGrantRequest(
                    binding,
                    new ProviderAccessScope(
                            ProviderCapabilities.of("repository.read"),
                            ProviderResourceScope.of("repository:crewscope")));
        }

        private ResponsibilityAssignment assignment(
                ResponsibilityRole role, Principal actor, Optional<TeamMemberId> memberId) {
            return ResponsibilityAssignment.reconstitute(
                    ResponsibilityAssignmentId.generate(), scope, workItem.id(), role,
                    actor.id(), actor.type(), memberId, ResponsibilityAssignmentStatus.ACTIVE,
                    owner.id(), CREATED, CREATED, Optional.empty(), Optional.empty(), 0,
                    AuditMetadata.createdBy(owner.id(), CREATED));
        }

        void seed(JdbcTemplate jdbc) {
            jdbc.update("INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                    organizationId.value(), "Org " + key);
            jdbc.update("INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                    teamId.value(), organizationId.value(), "Team " + key);
            jdbc.update("""
                    INSERT INTO crewscope.workspace (
                        id, organization_id, team_id, workspace_type, name, status
                    ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                    """, workspaceId.value(), organizationId.value(), teamId.value(), "Workspace " + key);
            jdbc.update("""
                    INSERT INTO crewscope.principal (
                        id, organization_id, team_id, principal_type,
                        display_name, visibility, status
                    ) VALUES (?, ?, ?, 'USER', 'Owner', 'TEAM', 'ACTIVE')
                    """, owner.id().value(), organizationId.value(), teamId.value());
            jdbc.update("""
                    INSERT INTO crewscope.principal (
                        id, organization_id, team_id, principal_type, owner_principal_id,
                        display_name, visibility, status
                    ) VALUES (?, ?, ?, 'TEAM_AGENT', ?, 'Executor', 'TEAM', 'ACTIVE')
                    """, executor.id().value(), organizationId.value(), teamId.value(), owner.id().value());
            jdbc.update("""
                    INSERT INTO crewscope.team_member (
                        id, organization_id, team_id, user_principal_id,
                        status, join_method, joined_at
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?)
                    """, ownerMemberId.value(), organizationId.value(), teamId.value(),
                    owner.id().value(), CREATED.toOffsetDateTime());
            jdbc.update("""
                    INSERT INTO crewscope.agent_profile (
                        id, organization_id, team_id, workspace_id, agent_principal_id,
                        profile_type, default_profile, status,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, 'TEAM', FALSE, 'ACTIVE', ?, ?)
                    """, profileId.value(), organizationId.value(), teamId.value(),
                    workspaceId.value(), executor.id().value(), owner.id().value(), owner.id().value());
            jdbc.update("""
                    INSERT INTO crewscope.work_project (
                        id, organization_id, team_id, workspace_id, project_key, name,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, 'Project', ?, ?)
                    """, projectId.value(), organizationId.value(), teamId.value(),
                    workspaceId.value(), key, owner.id().value(), owner.id().value());
            jdbc.update("""
                    INSERT INTO crewscope.work_item (
                        id, organization_id, team_id, workspace_id, project_id,
                        item_key, item_type, title, status, priority, version,
                        created_at, updated_at, created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', 'Source task', 'READY', 'MEDIUM', 0,
                        ?, ?, ?, ?)
                    """, workItem.id().value(), organizationId.value(), teamId.value(),
                    workspaceId.value(), projectId.value(), workItem.key().value(),
                    CREATED.toOffsetDateTime(), CREATED.toOffsetDateTime(),
                    owner.id().value(), owner.id().value());
            seedAssignment(jdbc, ownerAssignment);
            seedAssignment(jdbc, executorAssignment);
        }

        void seedProviderBinding(JdbcTemplate jdbc) {
            jdbc.update(
                    """
                    INSERT INTO crewscope.provider_definition (
                        id, organization_id, provider_key, provider_type,
                        interface_version, display_name, capabilities, status,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, 'SOURCE_CODE', 'v1', 'Repository',
                        '["repository.read"]'::jsonb, 'ACTIVE', ?, ?)
                    """,
                    providerDefinitionId.value(), organizationId.value(),
                    "repository-" + key.toLowerCase(), owner.id().value(), owner.id().value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.provider_implementation (
                        id, organization_id, provider_definition_id, provider_type,
                        definition_interface_version, implementation_key,
                        implementation_version, capabilities,
                        connection_requirement, status,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, 'SOURCE_CODE', 'v1', 'repository-local', '1.0.0',
                        '["repository.read"]'::jsonb, 'NONE', 'ACTIVE', ?, ?)
                    """,
                    providerImplementationId.value(), organizationId.value(),
                    providerDefinitionId.value(), owner.id().value(), owner.id().value());
            jdbc.update(
                    """
                    INSERT INTO crewscope.provider_binding (
                        id, organization_id, team_id, workspace_id, target_type, work_project_id,
                        owner_type, owner_id, provider_definition_id,
                        provider_definition_version, provider_type,
                        provider_implementation_id, provider_implementation_version,
                        connection_requirement, effective_capabilities,
                        resource_unrestricted, effective_resources, default_usage, status,
                        created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, 'WORK_PROJECT', ?, 'ORGANIZATION', ?, ?, 0,
                        'SOURCE_CODE', ?, 0, 'NONE', '["repository.read"]'::jsonb,
                        FALSE, '["repository:crewscope"]'::jsonb, FALSE, 'ACTIVE', ?, ?)
                    """,
                    providerBindingId.value(), organizationId.value(), teamId.value(),
                    workspaceId.value(), projectId.value(), organizationId.value(),
                    providerDefinitionId.value(), providerImplementationId.value(),
                    owner.id().value(), owner.id().value());
        }

        private void seedAssignment(JdbcTemplate jdbc, ResponsibilityAssignment assignment) {
            jdbc.update("""
                    INSERT INTO crewscope.responsibility_assignment (
                        id, organization_id, team_id, workspace_id, project_id, work_item_id,
                        role, actor_principal_id, actor_type, actor_member_id, status,
                        assigned_by_principal_id, assigned_at, accepted_at, version,
                        created_at, updated_at, created_by_principal_id, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0, ?, ?, ?, ?)
                    """, assignment.id().value(), organizationId.value(), teamId.value(),
                    workspaceId.value(), projectId.value(), workItem.id().value(),
                    assignment.role().name(), assignment.actorPrincipalId().value(),
                    assignment.actorType().name(),
                    assignment.actorMemberId().map(TeamMemberId::value).orElse(null),
                    owner.id().value(), CREATED.toOffsetDateTime(), CREATED.toOffsetDateTime(),
                    CREATED.toOffsetDateTime(), CREATED.toOffsetDateTime(),
                    owner.id().value(), owner.id().value());
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
        JpaPolicySnapshotRepositoryAdapter.class,
        JpaSafetyEnforcementOverlayRepositoryAdapter.class,
        JpaPlanVersionRepositoryAdapter.class,
        JpaStepExecutionRepositoryAdapter.class,
        JpaTaskCredentialGrantRepositoryAdapter.class,
        JpaTaskAgentRuntimeSessionRepositoryAdapter.class,
        JpaAgentRunRepositoryAdapter.class,
        JpaAgentInterruptRepositoryAdapter.class,
        JpaRuntimeArtifactRepositoryAdapter.class,
        JpaAgentStateSnapshotRepositoryAdapter.class,
        JdbcTaskRuntimeEventReceiptRepository.class
    })
    static class TestApplication {}
}
