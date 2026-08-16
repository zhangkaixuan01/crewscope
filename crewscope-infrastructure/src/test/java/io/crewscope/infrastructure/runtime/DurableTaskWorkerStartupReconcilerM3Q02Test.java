package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.LeaseSweepResult;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Fixed process-exit matrix for CLAIMED, PREPARING and RUNNING startup recovery. */
class DurableTaskWorkerStartupReconcilerM3Q02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-16T01:00:00Z");

    @ParameterizedTest(name = "process exits while attempt is {0}")
    @EnumSource(value = TaskExecutionStatus.class, names = {"CLAIMED", "PREPARING", "RUNNING"})
    void startupReconciliationConvergesOnceFromPersistedRecoveryState(
            TaskExecutionStatus exitStage) {
        Fixture fixture = fixture(exitStage);

        assertEquals(TaskExecutionStatus.RECOVERING, fixture.recoveringExecution().status());
        assertEquals(1, fixture.reconciler().reconcile());
        assertEquals(0, fixture.reconciler().reconcile());

        // A restarted Worker may retry startup reconciliation, but each durable fact is written once.
        verify(fixture.sweeper(), times(2)).sweep(10);
        verify(fixture.executionRepository(), times(1)).update(fixture.readyExecution());
        verify(fixture.recoveringExecution(), times(1)).requeue(
                any(), any(Long.class), any(), any());
        if (exitStage == TaskExecutionStatus.RUNNING) {
            verify(fixture.runRepository(), times(1)).update(fixture.failedRun());
            verify(fixture.stepRepository(), times(1)).update(fixture.failedStep());
        } else {
            verify(fixture.runRepository(), never()).update(any());
            verify(fixture.stepRepository(), never()).update(any());
        }
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(TaskExecutionStatus exitStage) {
        OrganizationId organizationId = OrganizationId.generate();
        Principal runtimeActor = principal(organizationId, "Runtime Worker");
        Principal taskAgent = principal(organizationId, "Task Agent");
        RuntimeCapabilities capabilities = new RuntimeCapabilities(
                Set.of(RuntimeCapability.PLAN, RuntimeCapability.SESSION_STATE));
        RuntimeWorkerRegistrationSpec registration = new RuntimeWorkerRegistrationSpec(
                organizationId,
                new RuntimeEnvironment("test"),
                "agentscope-java",
                "AgentScope Java",
                "2.0.0",
                capabilities,
                "worker-m3-q02",
                RuntimeProfile.WORKER,
                capabilities,
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                runtimeActor);

        TaskExecutionId executionId = TaskExecutionId.generate();
        TaskExecution recovering = execution(TaskExecutionStatus.RECOVERING, executionId);
        TaskExecution ready = execution(TaskExecutionStatus.READY);
        when(recovering.requeue(any(), any(Long.class), any(), any())).thenReturn(ready);

        ExecutionLeaseSweeper sweeper = mock(ExecutionLeaseSweeper.class);
        when(sweeper.sweep(10)).thenReturn(
                new LeaseSweepResult(List.of(new LeaseSweepResult.RecoveredLease(
                        ExecutionLeaseId.generate(), executionId))),
                new LeaseSweepResult(List.of()));
        TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        when(executions.findRecoveringForUpdate(organizationId, 10))
                .thenReturn(List.of(recovering), List.of());
        ExecutionLeaseRepository leases = mock(ExecutionLeaseRepository.class);
        when(leases.findActiveByTaskExecution(organizationId, recovering.id()))
                .thenReturn(Optional.empty());
        AgentRunRepository runs = mock(AgentRunRepository.class);
        StepExecutionRepository steps = mock(StepExecutionRepository.class);
        PrincipalRepository principals = mock(PrincipalRepository.class);
        when(principals.findById(organizationId, taskAgent.id()))
                .thenReturn(Optional.of(taskAgent));

        AgentRun failedRun = mock(AgentRun.class);
        StepExecution failedStep = mock(StepExecution.class);
        if (exitStage == TaskExecutionStatus.RUNNING) {
            AgentRun running = mock(AgentRun.class);
            when(running.status()).thenReturn(AgentRunStatus.RUNNING);
            when(running.agentPrincipalId()).thenReturn(taskAgent.id());
            when(running.version()).thenReturn(2L);
            when(running.fail(
                            any(String.class),
                            org.mockito.ArgumentMatchers
                                    .<Optional<io.crewscope.domain.task.RuntimeArtifact>>any(),
                            any(Long.class),
                            any(),
                            any()))
                    .thenReturn(failedRun);
            when(runs.findByExecution(organizationId, recovering.id()))
                    .thenReturn(List.of(running));

            StepExecution runningStep = mock(StepExecution.class);
            ExecutionPrincipalSnapshot principal = mock(ExecutionPrincipalSnapshot.class);
            when(principal.principalId()).thenReturn(taskAgent.id());
            when(runningStep.status()).thenReturn(StepExecutionStatus.RUNNING);
            when(runningStep.executionPrincipal()).thenReturn(principal);
            when(runningStep.version()).thenReturn(3L);
            when(runningStep.fail(any(), any(Long.class), any(), any()))
                    .thenReturn(failedStep);
            when(steps.findByExecution(organizationId, recovering.id()))
                    .thenReturn(List.of(runningStep));
        } else {
            when(runs.findByExecution(organizationId, recovering.id())).thenReturn(List.of());
            when(steps.findByExecution(organizationId, recovering.id())).thenReturn(List.of());
        }

        DurableTaskWorkerStartupReconciler reconciler = new DurableTaskWorkerStartupReconciler(
                sweeper,
                executions,
                leases,
                steps,
                runs,
                principals,
                new DirectTransactions(),
                () -> NOW,
                registration,
                10);
        return new Fixture(
                recovering,
                ready,
                failedRun,
                failedStep,
                sweeper,
                executions,
                steps,
                runs,
                reconciler);
    }

    private static TaskExecution execution(TaskExecutionStatus status) {
        return execution(status, TaskExecutionId.generate());
    }

    private static TaskExecution execution(TaskExecutionStatus status, TaskExecutionId id) {
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(id);
        when(execution.status()).thenReturn(status);
        when(execution.version()).thenReturn(1L);
        return execution;
    }

    private static Principal principal(OrganizationId organizationId, String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private static final class DirectTransactions implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }

    private record Fixture(
            TaskExecution recoveringExecution,
            TaskExecution readyExecution,
            AgentRun failedRun,
            StepExecution failedStep,
            ExecutionLeaseSweeper sweeper,
            TaskExecutionRepository executionRepository,
            StepExecutionRepository stepRepository,
            AgentRunRepository runRepository,
            DurableTaskWorkerStartupReconciler reconciler) {}
}
