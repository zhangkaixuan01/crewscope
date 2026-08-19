package io.crewscope.infrastructure.runtime;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import java.util.Objects;

/** Repairs expired CLAIMED/PREPARING/RUNNING attempts before a restarted Worker claims work. */
public final class DurableTaskWorkerStartupReconciler implements TaskWorkerStartupReconciler {

    private static final TaskExecutionFailure RECOVERY_FAILURE = new TaskExecutionFailure(
            TaskExecutionFailureClass.RECOVERY_INTERRUPTED, "WORKER_PROCESS_LOST");

    private final ExecutionLeaseSweeper leaseSweeper;
    private final TaskExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final StepExecutionRepository stepRepository;
    private final AgentRunRepository runRepository;
    private final PrincipalRepository principalRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final RuntimeWorkerRegistrationSpec registration;
    private final int maximumReconcileSize;
    private final TaskExecutionRecoveryObserver recoveryObserver;

    public DurableTaskWorkerStartupReconciler(
            ExecutionLeaseSweeper leaseSweeper,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            StepExecutionRepository stepRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            int maximumReconcileSize) {
        this(
                leaseSweeper,
                executionRepository,
                leaseRepository,
                stepRepository,
                runRepository,
                principalRepository,
                transactionExecutor,
                timeProvider,
                registration,
                maximumReconcileSize,
                TaskExecutionRecoveryObserver.NOOP);
    }

    public DurableTaskWorkerStartupReconciler(
            ExecutionLeaseSweeper leaseSweeper,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            StepExecutionRepository stepRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            int maximumReconcileSize,
            TaskExecutionRecoveryObserver recoveryObserver) {
        this.leaseSweeper = Objects.requireNonNull(leaseSweeper, "leaseSweeper");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.stepRepository = Objects.requireNonNull(stepRepository, "stepRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.recoveryObserver = Objects.requireNonNull(recoveryObserver, "recoveryObserver");
        if (maximumReconcileSize < 1 || maximumReconcileSize > 10_000) {
            throw new IllegalArgumentException("maximumReconcileSize must be between 1 and 10000");
        }
        this.maximumReconcileSize = maximumReconcileSize;
    }

    @Override
    public int reconcile() {
        // Expiry is established only by the same PostgreSQL-time Sweeper used during steady state.
        leaseSweeper.sweep(maximumReconcileSize);
        return transactionExecutor.required(this::reconcileTransaction);
    }

    private int reconcileTransaction() {
        UtcTimestamp now = timeProvider.now();
        int reconciled = 0;
        for (TaskExecution execution : executionRepository.findRecoveringForUpdate(
                registration.organizationId(), maximumReconcileSize)) {
            if (leaseRepository.findActiveByTaskExecution(
                    registration.organizationId(), execution.id()).isPresent()) {
                throw new IllegalStateException(
                        "RECOVERING TaskExecution must not retain an active Lease");
            }
            recoveryObserver.beforeRequeue(execution, now);
            closeOrphanRuns(execution, now);
            closeOrphanSteps(execution, now);
            TaskExecution ready = execution.requeue(
                    now, execution.version(), registration.actor(), now);
            executionRepository.update(ready);
            reconciled++;
        }
        return reconciled;
    }

    private void closeOrphanRuns(TaskExecution execution, UtcTimestamp now) {
        runRepository.findByExecution(registration.organizationId(), execution.id()).stream()
                .filter(run -> run.status() == AgentRunStatus.RUNNING)
                .forEach(run -> {
                    Principal agent = principal(run);
                    AgentRun failed = run.fail(
                            "WORKER_PROCESS_LOST",
                            java.util.Optional.empty(),
                            run.version(),
                            agent,
                            now);
                    runRepository.update(failed);
                });
    }

    private void closeOrphanSteps(TaskExecution execution, UtcTimestamp now) {
        stepRepository.findByExecution(registration.organizationId(), execution.id()).stream()
                .filter(step -> step.status() == StepExecutionStatus.RUNNING)
                .forEach(step -> {
                    Principal agent = principal(step.executionPrincipal().principalId());
                    StepExecution failed = step.fail(
                            RECOVERY_FAILURE, step.version(), agent, now);
                    stepRepository.update(failed);
                });
    }

    private Principal principal(AgentRun run) {
        return principal(run.agentPrincipalId());
    }

    private Principal principal(io.crewscope.domain.shared.id.PrincipalId principalId) {
        Principal principal = principalRepository.findById(
                        registration.organizationId(), principalId)
                .orElseThrow(() -> new AggregateNotFoundException("Principal", principalId));
        if (!principal.canAct()) {
            throw new IllegalStateException(
                    "Orphan Agent Principal is not active during startup reconciliation");
        }
        return principal;
    }
}
