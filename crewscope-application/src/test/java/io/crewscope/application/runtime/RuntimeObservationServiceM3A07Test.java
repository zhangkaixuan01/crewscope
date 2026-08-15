package io.crewscope.application.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.ExecutionRuntimeStatus;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskExecutionWaiting;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Application evidence for M3-A07 health derivation, diagnostics and role separation. */
class RuntimeObservationServiceM3A07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-15T11:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final RuntimeEnvironment environment = new RuntimeEnvironment("development");
    private final TeamAccessContext context = mock(TeamAccessContext.class);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final RuntimeObservationRepository repository = mock(RuntimeObservationRepository.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    @Test
    void returnsOnlyClosedAggregateHealthAndFreshCapacityToMembers() {
        RuntimeCapabilities capabilities = RuntimeCapabilities.of(RuntimeCapability.TASK_EXECUTION);
        ExecutionRuntime runtime = runtime(capabilities);
        RuntimeWorker worker = worker(runtime, capabilities, RuntimeWorkerStatus.ACTIVE, true, true, 4, 1);
        when(repository.observe(any())).thenReturn(
                new RuntimeObservationSnapshot(List.of(runtime), List.of(worker), List.of()));

        RuntimeFleetSummary result = service(repository).summary(
                context, organizationId, teamId, environment);

        assertEquals(RuntimeFleetHealth.HEALTHY, result.health());
        assertEquals(new RuntimeCapacitySummary(4, 1, 3), result.capacity());
        assertEquals(1, result.activeWorkerCount());
        assertEquals(0, result.waitingRuntimeExecutions());
        verify(accessPolicy).requireVisibleTeam(context, organizationId, teamId);
    }

    @Test
    void operationsFailBeforeReadingRegistryWhenTeamObserveIsMissing() {
        doThrow(new PolicyDeniedException("observe Runtime operations details"))
                .when(accessPolicy)
                .requireTeamPermission(any(), any(), any(), any(), any(), any());

        assertThrows(PolicyDeniedException.class, () -> service(repository).operations(
                context, organizationId, teamId, environment));

        verify(repository, never()).observe(any());
    }

    @Test
    void excludesWorkersUnderInactiveRuntimesFromClaimableCapacity() {
        RuntimeCapabilities capabilities = RuntimeCapabilities.of(RuntimeCapability.TASK_EXECUTION);
        ExecutionRuntime activeRuntime = runtime(capabilities);
        ExecutionRuntime disabledRuntime = runtime(capabilities, ExecutionRuntimeStatus.DISABLED);
        RuntimeWorker activeWorker = worker(
                activeRuntime, capabilities, RuntimeWorkerStatus.ACTIVE, true, true, 2, 1);
        RuntimeWorker unavailableWorker = worker(
                disabledRuntime, capabilities, RuntimeWorkerStatus.ACTIVE, true, false, 20, 10);
        when(repository.observe(any())).thenReturn(new RuntimeObservationSnapshot(
                List.of(activeRuntime, disabledRuntime),
                List.of(activeWorker, unavailableWorker),
                List.of()));

        RuntimeFleetSummary result = service(repository).summary(
                context, organizationId, teamId, environment);

        assertEquals(new RuntimeCapacitySummary(2, 1, 1), result.capacity());
        assertEquals(1, result.activeWorkerCount());
        assertEquals(RuntimeFleetHealth.HEALTHY, result.health());
    }

    @Test
    void marksAWorkerUnderAnInactiveRuntimeAsRuntimeUnavailable() {
        RuntimeCapabilities capabilities = RuntimeCapabilities.of(RuntimeCapability.TASK_EXECUTION);
        ExecutionRuntime runtime = runtime(capabilities, ExecutionRuntimeStatus.DISABLED);
        RuntimeWorker worker = worker(
                runtime, capabilities, RuntimeWorkerStatus.ACTIVE, true, false, 2, 0);
        when(repository.observe(any())).thenReturn(
                new RuntimeObservationSnapshot(List.of(runtime), List.of(worker), List.of()));

        RuntimeOperationsView result = service(repository).operations(
                context, organizationId, teamId, environment);

        assertFalse(result.workers().get(0).runtimeActive());
        assertFalse(result.workers().get(0).claimable());
    }

    @Test
    void diagnosesEveryCurrentWaitingRuntimeCondition() {
        assertEquals(RuntimeWaitCause.CAPABILITY_UNAVAILABLE, diagnose(
                RuntimeCapabilities.of(RuntimeCapability.TASK_EXECUTION),
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeWorkerStatus.ACTIVE,
                true,
                true));
        assertEquals(RuntimeWaitCause.NO_ACTIVE_WORKER, diagnose(
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeWorkerStatus.DISABLED,
                true,
                false));
        assertEquals(RuntimeWaitCause.DRAINING, diagnose(
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeWorkerStatus.DRAINING,
                true,
                false));
        assertEquals(RuntimeWaitCause.HEARTBEAT_STALE, diagnose(
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeWorkerStatus.ACTIVE,
                false,
                false));
        assertEquals(RuntimeWaitCause.CAPACITY_EXHAUSTED, diagnose(
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeWorkerStatus.ACTIVE,
                true,
                false));
        assertEquals(RuntimeWaitCause.REQUEUE_PENDING, diagnose(
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeWorkerStatus.ACTIVE,
                true,
                true));
    }

    @Test
    void rejectsCrossTeamWaitingRowsFromThePersistencePort() {
        ExecutionRuntime runtime = runtime(RuntimeCapabilities.of(RuntimeCapability.PLAN));
        RuntimeWorker worker = worker(
                runtime,
                RuntimeCapabilities.of(RuntimeCapability.PLAN),
                RuntimeWorkerStatus.ACTIVE,
                true,
                true,
                2,
                0);
        RuntimeWaitingExecution leaked = waiting(
                RuntimeCapabilities.of(RuntimeCapability.PLAN), TeamId.generate());
        when(repository.observe(any())).thenReturn(new RuntimeObservationSnapshot(
                List.of(runtime), List.of(worker), List.of(leaked)));

        assertThrows(DomainValidationException.class, () -> service(repository).summary(
                context, organizationId, teamId, environment));
    }

    private RuntimeWaitCause diagnose(
            RuntimeCapabilities available,
            RuntimeCapabilities required,
            RuntimeWorkerStatus status,
            boolean fresh,
            boolean claimable) {
        RuntimeObservationRepository source = mock(RuntimeObservationRepository.class);
        ExecutionRuntime runtime = runtime(available);
        RuntimeWorker worker = worker(runtime, available, status, fresh, claimable, 1, claimable ? 0 : 1);
        RuntimeWaitingExecution waiting = waiting(required, teamId);
        when(source.observe(any())).thenReturn(new RuntimeObservationSnapshot(
                List.of(runtime),
                List.of(worker),
                List.of(waiting)));
        RuntimeFleetSummary result = service(source).summary(
                context, organizationId, teamId, environment);
        return result.waitingCauses().keySet().iterator().next();
    }

    private RuntimeObservationService service(RuntimeObservationRepository source) {
        return new RuntimeObservationService(
                accessPolicy, source, transactions, () -> NOW, TIMEOUT);
    }

    private ExecutionRuntime runtime(RuntimeCapabilities capabilities) {
        return runtime(capabilities, ExecutionRuntimeStatus.ACTIVE);
    }

    private ExecutionRuntime runtime(
            RuntimeCapabilities capabilities, ExecutionRuntimeStatus status) {
        ExecutionRuntime runtime = mock(ExecutionRuntime.class);
        when(runtime.id()).thenReturn(ExecutionRuntimeId.generate());
        when(runtime.organizationId()).thenReturn(organizationId);
        when(runtime.environment()).thenReturn(environment);
        when(runtime.status()).thenReturn(status);
        when(runtime.capabilities()).thenReturn(capabilities);
        when(runtime.supports(any(RuntimeCapabilities.class))).thenAnswer(invocation ->
                status == ExecutionRuntimeStatus.ACTIVE
                        && capabilities.supports(
                                invocation.getArgument(0, RuntimeCapabilities.class)));
        return runtime;
    }

    private RuntimeWorker worker(
            ExecutionRuntime runtime,
            RuntimeCapabilities capabilities,
            RuntimeWorkerStatus status,
            boolean fresh,
            boolean claimable,
            int maximum,
            int active) {
        RuntimeWorker worker = mock(RuntimeWorker.class);
        when(worker.id()).thenReturn(RuntimeWorkerId.generate());
        when(worker.organizationId()).thenReturn(organizationId);
        when(worker.environment()).thenReturn(environment);
        ExecutionRuntimeId runtimeId = runtime.id();
        when(worker.runtimeId()).thenReturn(runtimeId);
        when(worker.capabilities()).thenReturn(capabilities);
        when(worker.status()).thenReturn(status);
        when(worker.capacity()).thenReturn(new RuntimeWorkerCapacity(maximum, active));
        when(worker.isHeartbeatFresh(NOW, TIMEOUT)).thenReturn(fresh);
        when(worker.canClaim(any(), any(), any(), any())).thenReturn(claimable);
        return worker;
    }

    private RuntimeWaitingExecution waiting(
            RuntimeCapabilities required, TeamId waitingTeamId) {
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(TaskExecutionId.generate());
        when(execution.taskId()).thenReturn(TaskId.generate());
        when(execution.attempt()).thenReturn(1);
        when(execution.scope()).thenReturn(new WorkItemScope(
                organizationId,
                waitingTeamId,
                WorkspaceId.generate(),
                WorkProjectId.generate()));
        when(execution.status()).thenReturn(TaskExecutionStatus.WAITING);
        when(execution.waiting()).thenReturn(Optional.of(
                new TaskExecutionWaiting(TaskExecutionWaitReason.RUNTIME, NOW)));
        return new RuntimeWaitingExecution(execution, required);
    }
}
