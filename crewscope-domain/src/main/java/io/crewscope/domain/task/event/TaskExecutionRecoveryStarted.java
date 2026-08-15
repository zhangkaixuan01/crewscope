package io.crewscope.domain.task.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.TaskExecution;
import java.util.Objects;
import java.util.UUID;

/** Unique durable fact emitted after an expired Lease enters RECOVERING. */
public record TaskExecutionRecoveryStarted(
        UUID leaseId,
        int attempt,
        long fencingToken,
        ExecutionLeasePhase expiredPhase,
        UtcTimestamp leaseExpiredAt,
        UtcTimestamp recoveryStartedAt)
        implements DomainEvent {

    public TaskExecutionRecoveryStarted {
        leaseId = AggregateId.requireValue(leaseId, "leaseId");
        if (attempt < 1 || fencingToken < 1) {
            throw new IllegalArgumentException("attempt and fencingToken must be positive");
        }
        Objects.requireNonNull(expiredPhase, "expiredPhase");
        Objects.requireNonNull(leaseExpiredAt, "leaseExpiredAt");
        Objects.requireNonNull(recoveryStartedAt, "recoveryStartedAt");
        if (recoveryStartedAt.compareTo(leaseExpiredAt) < 0) {
            throw new IllegalArgumentException("recovery cannot start before Lease expiry");
        }
    }

    public static TaskExecutionRecoveryStarted from(
            TaskExecution recovering, ExecutionLease expiredLease, UtcTimestamp occurredAt) {
        TaskExecution execution = Objects.requireNonNull(recovering, "recovering");
        ExecutionLease lease = Objects.requireNonNull(expiredLease, "expiredLease");
        if (!execution.id().equals(lease.taskExecutionId())) {
            throw new IllegalArgumentException("execution and Lease must match");
        }
        return new TaskExecutionRecoveryStarted(
                lease.id().value(),
                lease.attempt(),
                lease.fencingToken().value(),
                lease.phase(),
                lease.expiresAt(),
                occurredAt);
    }
}
