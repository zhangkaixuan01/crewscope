package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Ensures M4 Workspace recovery is durable before M3 makes the attempt claimable again. */
class DurableTaskWorkerStartupReconcilerM4I10Test {

    @Test
    void invokesRecoveryObserverBeforeTaskExecutionUpdate() {
        OrganizationId organizationId = OrganizationId.generate();
        UtcTimestamp now = UtcTimestamp.parse("2026-08-19T04:00:00Z");
        TaskExecution execution = mock(TaskExecution.class);
        TaskExecution ready = mock(TaskExecution.class);
        TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        ExecutionLeaseRepository leases = mock(ExecutionLeaseRepository.class);
        StepExecutionRepository steps = mock(StepExecutionRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        RuntimeWorkerRegistrationSpec registration = mock(RuntimeWorkerRegistrationSpec.class);
        TaskExecutionRecoveryObserver observer = mock(TaskExecutionRecoveryObserver.class);
        when(registration.organizationId()).thenReturn(organizationId);
        when(executions.findRecoveringForUpdate(organizationId, 10)).thenReturn(List.of(execution));
        when(leases.findActiveByTaskExecution(organizationId, execution.id()))
                .thenReturn(Optional.empty());
        when(steps.findByExecution(organizationId, execution.id())).thenReturn(List.of());
        when(runs.findByExecution(organizationId, execution.id())).thenReturn(List.of());
        when(execution.requeue(now, execution.version(), registration.actor(), now))
                .thenReturn(ready);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());

        DurableTaskWorkerStartupReconciler reconciler = new DurableTaskWorkerStartupReconciler(
                mock(ExecutionLeaseSweeper.class),
                executions,
                leases,
                steps,
                runs,
                mock(PrincipalRepository.class),
                transactions,
                (AuthoritativeTimeProvider) () -> now,
                registration,
                10,
                observer);

        assertEquals(1, reconciler.reconcile());
        InOrder order = inOrder(observer, executions);
        order.verify(observer).beforeRequeue(execution, now);
        order.verify(executions).update(ready);
    }
}
