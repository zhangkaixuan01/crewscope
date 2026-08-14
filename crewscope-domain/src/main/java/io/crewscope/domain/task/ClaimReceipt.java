package io.crewscope.domain.task;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** One-time trusted response produced after TaskExecution and Lease commit atomically. */
public record ClaimReceipt(
        ExecutionLeaseId leaseId,
        TaskExecutionId taskExecutionId,
        int attempt,
        ExecutionRuntimeId runtimeId,
        RuntimeWorkerId workerId,
        ClaimToken claimToken,
        FencingToken fencingToken,
        long taskExecutionVersion,
        long leaseVersion,
        UtcTimestamp expiresAt) {

    public ClaimReceipt {
        leaseId = Objects.requireNonNull(leaseId, "leaseId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "claimReceipt.attempt", "must be positive");
        }
        runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        claimToken = Objects.requireNonNull(claimToken, "claimToken");
        fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
        if (taskExecutionVersion < 0 || leaseVersion < 0) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "claimReceipt.version", "must not be negative");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public LeaseOwnership ownership() {
        return new LeaseOwnership(
                taskExecutionId, attempt, runtimeId, workerId, claimToken.hash(), fencingToken);
    }

    /** Prevents accidental disclosure when records are interpolated into logs. */
    @Override
    public String toString() {
        return "ClaimReceipt[leaseId=" + leaseId
                + ", taskExecutionId=" + taskExecutionId
                + ", attempt=" + attempt
                + ", runtimeId=" + runtimeId
                + ", workerId=" + workerId
                + ", claimToken=[REDACTED]"
                + ", fencingToken=" + fencingToken.value()
                + ", taskExecutionVersion=" + taskExecutionVersion
                + ", leaseVersion=" + leaseVersion
                + ", expiresAt=" + expiresAt + "]";
    }
}
