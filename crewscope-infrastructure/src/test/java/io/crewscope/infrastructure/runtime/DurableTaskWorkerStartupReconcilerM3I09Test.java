package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.LeaseSweepResult;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
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
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Startup scan proof for expired stage recovery and orphan Run/Step cleanup. */
class DurableTaskWorkerStartupReconcilerM3I09Test {

    @Test
    void requeuesExpiredClaimedPreparingAndRunningAttemptsWithoutOrphans() {
        Fixture fixture = fixture();
        TaskExecution claimed = execution(1);
        TaskExecution preparing = execution(2);
        TaskExecution running = execution(3);
        when(fixture.executionRepository.findRecoveringForUpdate(
                fixture.registration.organizationId(), 10))
                .thenReturn(List.of(claimed, preparing, running));
        for (TaskExecution execution : List.of(claimed, preparing, running)) {
            when(fixture.leaseRepository.findActiveByTaskExecution(
                    fixture.registration.organizationId(), execution.id()))
                    .thenReturn(Optional.empty());
            when(execution.requeue(any(), any(Long.class), any(), any()))
                    .thenReturn(mock(TaskExecution.class));
            when(fixture.runRepository.findByExecution(
                    fixture.registration.organizationId(), execution.id()))
                    .thenReturn(List.of());
            when(fixture.stepRepository.findByExecution(
                    fixture.registration.organizationId(), execution.id()))
                    .thenReturn(List.of());
        }
        AgentRun orphanRun = mock(AgentRun.class);
        when(orphanRun.status()).thenReturn(AgentRunStatus.RUNNING);
        when(orphanRun.agentPrincipalId()).thenReturn(fixture.agent.id());
        when(orphanRun.version()).thenReturn(4L);
        when(orphanRun.fail(any(), any(), any(Long.class), any(), any()))
                .thenReturn(mock(AgentRun.class));
        when(fixture.runRepository.findByExecution(
                fixture.registration.organizationId(), running.id()))
                .thenReturn(List.of(orphanRun));
        StepExecution orphanStep = mock(StepExecution.class);
        when(orphanStep.status()).thenReturn(StepExecutionStatus.RUNNING);
        ExecutionPrincipalSnapshot principal = mock(ExecutionPrincipalSnapshot.class);
        when(principal.principalId()).thenReturn(fixture.agent.id());
        when(orphanStep.executionPrincipal()).thenReturn(principal);
        when(orphanStep.version()).thenReturn(2L);
        when(orphanStep.fail(any(), any(Long.class), any(), any()))
                .thenReturn(mock(StepExecution.class));
        when(fixture.stepRepository.findByExecution(
                fixture.registration.organizationId(), running.id()))
                .thenReturn(List.of(orphanStep));

        assertEquals(3, fixture.reconciler.reconcile());

        var order = inOrder(fixture.sweeper, fixture.executionRepository);
        order.verify(fixture.sweeper).sweep(10);
        order.verify(fixture.executionRepository).findRecoveringForUpdate(
                fixture.registration.organizationId(), 10);
        verify(fixture.runRepository).update(any());
        verify(fixture.stepRepository).update(any());
        verify(fixture.executionRepository, org.mockito.Mockito.times(3)).update(any());
    }

    @Test
    void activeLeaseOnRecoveringAttemptFailsClosedBeforeRequeue() {
        Fixture fixture = fixture();
        TaskExecution execution = execution(1);
        when(fixture.executionRepository.findRecoveringForUpdate(
                fixture.registration.organizationId(), 10))
                .thenReturn(List.of(execution));
        when(fixture.leaseRepository.findActiveByTaskExecution(
                fixture.registration.organizationId(), execution.id()))
                .thenReturn(Optional.of(mock(ExecutionLease.class)));

        assertThrows(IllegalStateException.class, fixture.reconciler::reconcile);
        verify(fixture.executionRepository, never()).update(any());
    }

    private static TaskExecution execution(int sequence) {
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(TaskExecutionId.generate());
        when(execution.version()).thenReturn((long) sequence);
        return execution;
    }

    private static Fixture fixture() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = principal(organizationId, "Runtime Worker");
        Principal agent = principal(organizationId, "Task Agent");
        RuntimeCapabilities capabilities = new RuntimeCapabilities(
                Set.of(RuntimeCapability.PLAN, RuntimeCapability.SESSION_STATE));
        RuntimeWorkerRegistrationSpec registration = new RuntimeWorkerRegistrationSpec(
                organizationId,
                new RuntimeEnvironment("test"),
                "agentscope-java",
                "AgentScope Java",
                "2.0.0",
                capabilities,
                "worker-a",
                RuntimeProfile.WORKER,
                capabilities,
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                actor);
        ExecutionLeaseSweeper sweeper = mock(ExecutionLeaseSweeper.class);
        when(sweeper.sweep(10)).thenReturn(new LeaseSweepResult(List.of()));
        TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        ExecutionLeaseRepository leases = mock(ExecutionLeaseRepository.class);
        StepExecutionRepository steps = mock(StepExecutionRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        PrincipalRepository principals = mock(PrincipalRepository.class);
        when(principals.findById(organizationId, agent.id())).thenReturn(Optional.of(agent));
        AuthoritativeTimeProvider time = () -> UtcTimestamp.parse("2026-08-15T06:00:00Z");
        DurableTaskWorkerStartupReconciler reconciler = new DurableTaskWorkerStartupReconciler(
                sweeper,
                executions,
                leases,
                steps,
                runs,
                principals,
                new DirectTransactionExecutor(),
                time,
                registration,
                10);
        return new Fixture(
                registration,
                agent,
                sweeper,
                executions,
                leases,
                steps,
                runs,
                reconciler);
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
                UtcTimestamp.parse("2026-08-15T05:59:00Z"));
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }

    private record Fixture(
            RuntimeWorkerRegistrationSpec registration,
            Principal agent,
            ExecutionLeaseSweeper sweeper,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            StepExecutionRepository stepRepository,
            AgentRunRepository runRepository,
            DurableTaskWorkerStartupReconciler reconciler) {}
}
