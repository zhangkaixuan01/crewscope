package io.crewscope.domain.task;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import java.util.Objects;

/** Complete non-secret ownership coordinates required by every Worker mutation. */
public record LeaseOwnership(
        TaskExecutionId taskExecutionId,
        int attempt,
        ExecutionRuntimeId runtimeId,
        RuntimeWorkerId workerId,
        ClaimTokenHash claimTokenHash,
        FencingToken fencingToken) {

    public LeaseOwnership {
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "leaseOwnership.attempt", "must be positive");
        }
        runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        claimTokenHash = Objects.requireNonNull(claimTokenHash, "claimTokenHash");
        fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
    }
}
