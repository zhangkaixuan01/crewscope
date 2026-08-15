package io.crewscope.infrastructure.runtime;

import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.LeaseCommandScope;
import io.crewscope.application.task.LeaseCoordinatorMetrics;
import io.crewscope.application.task.LeaseCoordinatorOperation;
import io.crewscope.application.task.LeaseCoordinatorOutcome;
import io.crewscope.application.task.LeaseExecutionCommand;
import io.crewscope.application.task.LeaseHeartbeatCommand;
import io.crewscope.application.task.LeaseMutationResult;
import io.crewscope.application.task.LeaseReleaseCommand;
import io.crewscope.application.task.LeaseTransitionCommand;
import io.crewscope.application.task.OwnedTaskExecutionMutation;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.TaskExecution;
import java.util.Objects;
import java.util.function.Supplier;

/** Durable Worker command boundary backed by authoritative time and full ownership fencing. */
public final class DurableTaskExecutionLeaseCoordinator
        implements TaskExecutionLeaseCoordinator {

    private final TaskExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final LeaseCoordinatorMetrics metrics;
    private final ExecutionLeaseCoordinatorSpec spec;

    public DurableTaskExecutionLeaseCoordinator(
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            LeaseCoordinatorMetrics metrics,
            ExecutionLeaseCoordinatorSpec spec) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository, "executionRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    public TaskExecution beginPreparing(LeaseExecutionCommand command) {
        return measured(LeaseCoordinatorOperation.PREPARE, () -> transactionExecutor.required(() -> {
            LeaseExecutionCommand required = Objects.requireNonNull(command, "command");
            UtcTimestamp now = timeProvider.now();
            OwnershipState state = requireActiveOwnership(required.scope(), now);
            TaskExecution preparing = state.execution().beginPreparing(
                    required.expectedExecutionVersion(), spec.actor(), now);
            return leaseRepository.updateOwned(preparing, state.lease(), now);
        }));
    }

    @Override
    public LeaseMutationResult beginRun(LeaseTransitionCommand command) {
        return measured(LeaseCoordinatorOperation.BEGIN_RUN, () -> transactionExecutor.required(() -> {
            LeaseTransitionCommand required = Objects.requireNonNull(command, "command");
            UtcTimestamp now = timeProvider.now();
            OwnershipState state = requireActiveOwnership(required.scope(), now);
            TaskExecution running = state.execution().beginRunning(
                    required.expectedExecutionVersion(), spec.actor(), now);
            ExecutionLease runLease = state.lease().beginRun(
                    running,
                    required.scope().ownership(),
                    required.expectedLeaseVersion(),
                    now,
                    plus(now, spec.runLeaseDuration()));
            return new LeaseMutationResult(running, leaseRepository.switchPhase(running, runLease));
        }));
    }

    @Override
    public ExecutionLease heartbeat(LeaseHeartbeatCommand command) {
        return measured(LeaseCoordinatorOperation.HEARTBEAT, () -> transactionExecutor.required(() -> {
            LeaseHeartbeatCommand required = Objects.requireNonNull(command, "command");
            UtcTimestamp now = timeProvider.now();
            OwnershipState state = requireActiveOwnership(required.scope(), now);
            ExecutionLease renewed = state.lease().heartbeat(
                    required.scope().ownership(),
                    required.expectedLeaseVersion(),
                    now,
                    plus(now, spec.durationFor(state.lease().phase())));
            return leaseRepository.renew(renewed);
        }));
    }

    @Override
    public TaskExecution updateOwned(
            LeaseExecutionCommand command, OwnedTaskExecutionMutation mutation) {
        return measured(LeaseCoordinatorOperation.OWNED_UPDATE, () ->
                transactionExecutor.required(() -> {
                    LeaseExecutionCommand required = Objects.requireNonNull(command, "command");
                    OwnedTaskExecutionMutation requiredMutation = Objects.requireNonNull(
                            mutation, "mutation");
                    UtcTimestamp now = timeProvider.now();
                    OwnershipState state = requireActiveOwnership(required.scope(), now);
                    TaskExecution mutated = requiredMutation.apply(
                            state.execution(),
                            required.expectedExecutionVersion(),
                            spec.actor(),
                            now);
                    return leaseRepository.updateOwned(mutated, state.lease(), now);
                }));
    }

    @Override
    public LeaseMutationResult release(LeaseReleaseCommand command) {
        return measured(LeaseCoordinatorOperation.RELEASE, () -> transactionExecutor.required(() -> {
            LeaseReleaseCommand required = Objects.requireNonNull(command, "command");
            LeaseTransitionCommand executionCommand = required.executionCommand();
            UtcTimestamp now = timeProvider.now();
            OwnershipState state = requireActiveOwnership(executionCommand.scope(), now);
            TaskExecution releasedExecution = releaseExecution(state.execution(), required, now);
            ExecutionLease releasedLease = state.lease().release(
                    releasedExecution,
                    executionCommand.scope().ownership(),
                    required.reason(),
                    executionCommand.expectedLeaseVersion(),
                    now);
            return new LeaseMutationResult(
                    releasedExecution,
                    leaseRepository.release(releasedExecution, releasedLease));
        }));
    }

    private TaskExecution releaseExecution(
            TaskExecution current, LeaseReleaseCommand command, UtcTimestamp now) {
        long expected = command.executionCommand().expectedExecutionVersion();
        return switch (command.reason()) {
            case COMPLETED -> current.complete(expected, spec.actor(), now);
            case FAILED -> current.fail(command.failure().orElseThrow(), expected, spec.actor(), now);
            case CANCELLED -> current.acknowledgeCancelled(expected, spec.actor(), now);
            case PAUSED -> current.acknowledgePaused(expected, spec.actor(), now);
            case WAITING -> current.waitFor(
                    command.waitReason().orElseThrow(), expected, spec.actor(), now);
            case MANUAL_TAKEOVER -> current.beginManualTakeover(expected, spec.actor(), now);
            case WORKER_SHUTDOWN -> current.beginRecovery(expected, spec.actor(), now);
            case EXPIRED -> throw new IllegalArgumentException("EXPIRED is Sweeper-only");
        };
    }

    private OwnershipState requireActiveOwnership(
            LeaseCommandScope commandScope, UtcTimestamp authoritativeNow) {
        LeaseCommandScope scope = Objects.requireNonNull(commandScope, "commandScope");
        ExecutionLease lease = leaseRepository.findById(
                        scope.organizationId(), scope.environment(), scope.leaseId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ExecutionLease", scope.leaseId()));
        if (!lease.owns(scope.ownership(), authoritativeNow)) {
            throw new DomainValidationException(
                    "executionLease.ownership",
                    "must match every current active Lease coordinate before mutation");
        }
        TaskExecution execution = executionRepository.findById(
                        scope.organizationId(), scope.ownership().taskExecutionId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", scope.ownership().taskExecutionId()));
        if (!execution.id().equals(lease.taskExecutionId())
                || execution.attempt() != lease.attempt()
                || execution.lastFencingToken().filter(lease.fencingToken()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "executionLease.taskExecutionId",
                    "must match the current TaskExecution attempt and fencing epoch");
        }
        return new OwnershipState(execution, lease);
    }

    private <T> T measured(LeaseCoordinatorOperation operation, Supplier<T> action) {
        try {
            T result = action.get();
            safeRecord(operation, LeaseCoordinatorOutcome.SUCCEEDED);
            return result;
        } catch (RuntimeException failure) {
            safeRecord(operation, LeaseCoordinatorOutcome.FAILED);
            throw failure;
        }
    }

    private void safeRecord(
            LeaseCoordinatorOperation operation, LeaseCoordinatorOutcome outcome) {
        try {
            metrics.record(operation, outcome, 1);
        } catch (RuntimeException ignored) {
            // Observability cannot turn a committed ownership mutation into an apparent failure.
        }
    }

    private static UtcTimestamp plus(UtcTimestamp now, java.time.Duration duration) {
        return UtcTimestamp.from(now.value().plus(duration));
    }

    private record OwnershipState(TaskExecution execution, ExecutionLease lease) {}
}
