package io.crewscope.infrastructure.runtime;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.ExecutionLeaseSweeper;
import io.crewscope.application.task.LeaseCoordinatorMetrics;
import io.crewscope.application.task.LeaseCoordinatorOperation;
import io.crewscope.application.task.LeaseCoordinatorOutcome;
import io.crewscope.application.task.LeaseSweepResult;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.event.TaskExecutionRecoveryStarted;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL SKIP LOCKED Sweeper that atomically fences expired owners into RECOVERING. */
public final class DurableExecutionLeaseSweeper implements ExecutionLeaseSweeper {

    public static final String RECOVERY_EVENT_TYPE = "TASK_EXECUTION_RECOVERY_STARTED";

    private final TaskExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final DomainEventStore domainEventStore;
    private final TaskEventRepository taskEventRepository;
    private final OutboxRepository outboxRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final LeaseCoordinatorMetrics metrics;
    private final ExecutionLeaseCoordinatorSpec spec;

    public DurableExecutionLeaseSweeper(
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            DomainEventStore domainEventStore,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            LeaseCoordinatorMetrics metrics,
            ExecutionLeaseCoordinatorSpec spec) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository, "executionRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
        this.taskEventRepository = Objects.requireNonNull(
                taskEventRepository, "taskEventRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    public LeaseSweepResult sweep(int requestedLimit) {
        if (requestedLimit < 1 || requestedLimit > spec.maximumSweepSize()) {
            throw new IllegalArgumentException(
                    "requestedLimit must be between 1 and " + spec.maximumSweepSize());
        }
        try {
            LeaseSweepResult result = transactionExecutor.required(() -> sweepTransaction(requestedLimit));
            safeRecord(LeaseCoordinatorOutcome.SUCCEEDED, 1);
            return result;
        } catch (RuntimeException failure) {
            safeRecord(LeaseCoordinatorOutcome.FAILED, 1);
            throw failure;
        }
    }

    private LeaseSweepResult sweepTransaction(int requestedLimit) {
        UtcTimestamp now = timeProvider.now();
        List<ExecutionLease> expired = leaseRepository.findExpired(
                spec.organizationId(), spec.environment(), now, requestedLimit);
        List<LeaseSweepResult.RecoveredLease> recovered = new ArrayList<>();
        for (ExecutionLease lease : expired) {
            // The locked Lease is revalidated by the domain at the exact database-time boundary.
            ExecutionLease expiredLease = lease.expire(lease.version(), now);
            TaskExecution current = executionRepository.findById(
                            spec.organizationId(), lease.taskExecutionId())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TaskExecution", lease.taskExecutionId()));
            TaskExecution recovering = current.beginRecovery(
                    current.version(), spec.actor(), now);
            leaseRepository.release(recovering, expiredLease);
            appendRecoveryEvent(recovering, expiredLease, now);
            recovered.add(new LeaseSweepResult.RecoveredLease(lease.id(), current.id()));
        }
        return new LeaseSweepResult(recovered);
    }

    private void appendRecoveryEvent(
            TaskExecution recovering, ExecutionLease expiredLease, UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<TaskExecutionRecoveryStarted> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(RECOVERY_EVENT_TYPE),
                SchemaVersion.V1,
                recovering.scope().organizationId(),
                Optional.of(recovering.scope().teamId()),
                Optional.of(recovering.scope().workspaceId()),
                AggregateReference.of("TASK_EXECUTION", recovering.id()),
                recovering.version(),
                EventActor.principal(EventActorType.SERVICE, spec.actor().id()),
                UUID.randomUUID(),
                Optional.empty(),
                Optional.of("lease-expired:" + expiredLease.id()),
                occurredAt,
                TaskExecutionRecoveryStarted.from(recovering, expiredLease, occurredAt));
        domainEventStore.append(event);
        taskEventRepository.append(
                TaskEventContext.lease(
                        recovering.taskId(), recovering.id(), expiredLease.id()),
                event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    }

    private void safeRecord(LeaseCoordinatorOutcome outcome, long amount) {
        try {
            metrics.record(LeaseCoordinatorOperation.SWEEP, outcome, amount);
        } catch (RuntimeException ignored) {
            // Metrics are deliberately outside the recovery transaction.
        }
    }
}
