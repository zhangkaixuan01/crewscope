package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.LeaseCoordinatorMetrics;
import io.crewscope.application.task.LeaseCoordinatorOperation;
import io.crewscope.application.task.LeaseCoordinatorOutcome;
import io.crewscope.application.task.LeaseSweepResult;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * M10-Q00 defect 8: during an api restart window a dying process can commit a late
 * TaskExecution or Lease write between the Sweeper's read and its update. The losing sweep
 * must re-read and retry instead of surfacing the conflict — startup reconciliation used to
 * abort and the recovering execution waited another full cycle.
 */
class DurableExecutionLeaseSweeperM10Q00Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-30T01:00:00Z");

    @Test
    void sweepReReadsAndRetriesWhenALateRestartWindowWriterWinsTheVersionRace() {
        Fixture fixture = new Fixture();
        // First attempt loses the version race to a dying process' late release; the retry
        // re-selects with FOR UPDATE SKIP LOCKED and commits on the fresh versions.
        OptimisticLockConflictException conflict = fixture.conflict();
        when(fixture.leases.release(any(), any()))
                .thenThrow(conflict)
                .thenReturn(null);

        LeaseSweepResult result = fixture.sweeper().sweep(10);

        assertEquals(1, result.recovered().size());
        assertEquals(fixture.leaseId(), result.recovered().get(0).leaseId());
        // Each attempt is a fresh transaction that re-reads the expired batch.
        verify(fixture.leases, times(2)).findExpired(
                fixture.organizationId(), fixture.environment(), NOW, 10);
        verify(fixture.leases, times(2)).release(fixture.recovering(), fixture.expiredLease());
        verify(fixture.metrics).record(
                LeaseCoordinatorOperation.SWEEP, LeaseCoordinatorOutcome.SUCCEEDED, 1);
        verify(fixture.metrics, never()).record(
                LeaseCoordinatorOperation.SWEEP, LeaseCoordinatorOutcome.FAILED, 1);
    }

    @Test
    void sweepFailsFastOnceTheConflictRetryBudgetIsExhausted() {
        Fixture fixture = new Fixture();
        OptimisticLockConflictException conflict = fixture.conflict();
        when(fixture.leases.release(any(), any())).thenThrow(conflict);

        assertThrows(OptimisticLockConflictException.class, () -> fixture.sweeper().sweep(10));

        verify(fixture.leases, times(4)).findExpired(
                fixture.organizationId(), fixture.environment(), NOW, 10);
        verify(fixture.metrics, never()).record(
                LeaseCoordinatorOperation.SWEEP, LeaseCoordinatorOutcome.SUCCEEDED, 1);
        verify(fixture.metrics).record(
                LeaseCoordinatorOperation.SWEEP, LeaseCoordinatorOutcome.FAILED, 1);
    }

    @Test
    void nonConflictFailuresEscapeWithoutAnyRetry() {
        Fixture fixture = new Fixture();
        when(fixture.leases.release(any(), any()))
                .thenThrow(new IllegalStateException("lease repository unavailable"));

        assertThrows(IllegalStateException.class, () -> fixture.sweeper().sweep(10));

        verify(fixture.leases, times(1)).findExpired(
                fixture.organizationId(), fixture.environment(), NOW, 10);
        verify(fixture.metrics).record(
                LeaseCoordinatorOperation.SWEEP, LeaseCoordinatorOutcome.FAILED, 1);
    }

    @Test
    void integrityViolationsFromConcurrentLeaseInsertsRetryLikeVersionConflicts() {
        Fixture fixture = new Fixture();
        // A restarted worker claiming the same execution can insert a replacement Lease
        // between the sweep's read and write — the constraint race is the same restart-window
        // family and must converge the same way, matching RuntimeRegistryCoordinator's retry
        // set.
        when(fixture.leases.release(any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate fencing epoch"))
                .thenReturn(null);

        LeaseSweepResult result = fixture.sweeper().sweep(10);

        assertEquals(1, result.recovered().size());
        verify(fixture.leases, times(2)).release(any(), any());
    }

    private static final class Fixture {

        private final OrganizationId organizationId = OrganizationId.generate();
        private final RuntimeEnvironment environment = new RuntimeEnvironment("test");
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final ExecutionLeaseId leaseId = ExecutionLeaseId.generate();
        private final TaskId taskId = TaskId.generate();
        // One instance shared by the spec and the beginRecovery stub — Principal has no value
        // equality, so a fresh actor() per call would never match the stub.
        private final Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Runtime Worker",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        private final ExecutionLeaseRepository leases = mock(ExecutionLeaseRepository.class);
        private final DomainEventStore events = mock(DomainEventStore.class);
        private final TaskEventRepository taskEvents = mock(TaskEventRepository.class);
        private final OutboxRepository outbox = mock(OutboxRepository.class);
        private final LeaseCoordinatorMetrics metrics = mock(LeaseCoordinatorMetrics.class);

        private final ExecutionLease expiredLease;
        private final TaskExecution recovering;
        private final ExecutionLease lease;
        private final TaskExecution running;
        private final DurableExecutionLeaseSweeper sweeper;

        private Fixture() {
            // Dependency order matters: lease.expire stubs the expired lease and
            // beginRecovery stubs the recovering execution before either is referenced.
            this.expiredLease = buildExpiredLease();
            this.recovering = buildRecovering();
            this.lease = lease();
            this.running = running();
            when(leases.findExpired(organizationId, environment, NOW, 10))
                    .thenReturn(List.of(lease));
            when(executions.findById(organizationId, executionId))
                    .thenReturn(Optional.of(running));
            this.sweeper = new DurableExecutionLeaseSweeper(
                    executions,
                    leases,
                    events,
                    taskEvents,
                    outbox,
                    new DirectTransactions(),
                    () -> NOW,
                    metrics,
                    new ExecutionLeaseCoordinatorSpec(
                            organizationId,
                            environment,
                            actor,
                            Duration.ofSeconds(30),
                            Duration.ofSeconds(45),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(10),
                            1000));
        }

        DurableExecutionLeaseSweeper sweeper() {
            return sweeper;
        }

        ExecutionLeaseRepository leases() {
            return leases;
        }

        LeaseCoordinatorMetrics metrics() {
            return metrics;
        }

        TaskExecution recovering() {
            return recovering;
        }

        ExecutionLease expiredLease() {
            return expiredLease;
        }

        ExecutionLeaseId leaseId() {
            return leaseId;
        }

        OrganizationId organizationId() {
            return organizationId;
        }

        RuntimeEnvironment environment() {
            return environment;
        }

        OptimisticLockConflictException conflict() {
            return new OptimisticLockConflictException(
                    "TaskExecution", executionId, running.version(), running.version() + 1);
        }

        private ExecutionLease lease() {
            ExecutionLease lease = mock(ExecutionLease.class);
            when(lease.id()).thenReturn(leaseId);
            when(lease.taskExecutionId()).thenReturn(executionId);
            when(lease.version()).thenReturn(3L);
            when(lease.attempt()).thenReturn(1);
            when(lease.fencingToken()).thenReturn(new FencingToken(1));
            when(lease.phase()).thenReturn(ExecutionLeasePhase.RUN);
            when(lease.expiresAt()).thenReturn(NOW);
            when(lease.expire(3L, NOW)).thenReturn(expiredLease);
            return lease;
        }

        private ExecutionLease buildExpiredLease() {
            ExecutionLease expired = mock(ExecutionLease.class);
            when(expired.id()).thenReturn(leaseId);
            when(expired.taskExecutionId()).thenReturn(executionId);
            when(expired.attempt()).thenReturn(1);
            when(expired.fencingToken()).thenReturn(new FencingToken(1));
            when(expired.phase()).thenReturn(ExecutionLeasePhase.RUN);
            when(expired.expiresAt()).thenReturn(NOW);
            return expired;
        }

        private TaskExecution running() {
            TaskExecution running = mock(TaskExecution.class);
            when(running.id()).thenReturn(executionId);
            when(running.status()).thenReturn(TaskExecutionStatus.RUNNING);
            when(running.version()).thenReturn(5L);
            when(running.beginRecovery(5L, actor, NOW)).thenReturn(recovering);
            return running;
        }

        private TaskExecution buildRecovering() {
            TaskExecution recovering = mock(TaskExecution.class);
            when(recovering.id()).thenReturn(executionId);
            when(recovering.taskId()).thenReturn(taskId);
            when(recovering.version()).thenReturn(6L);
            when(recovering.scope()).thenReturn(new WorkItemScope(
                    organizationId, TeamId.generate(), WorkspaceId.generate(),
                    WorkProjectId.generate()));
            return recovering;
        }

    }

    private static final class DirectTransactions implements TransactionExecutor {

        @Override
        public <T> T required(java.util.function.Supplier<T> operation) {
            return operation.get();
        }
    }
}
