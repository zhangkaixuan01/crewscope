package io.crewscope.infrastructure.runtime;

import java.time.Duration;
import java.util.Objects;

/** Runtime-side Token, Heartbeat and Snapshot policy for one claimed Task execution. */
public record TaskWorkerExecutionSpec(
        Duration taskTokenLifetime,
        Duration leaseHeartbeatInterval,
        int recoveryCandidateLimit) {

    public TaskWorkerExecutionSpec {
        taskTokenLifetime = requirePositive(taskTokenLifetime, "taskTokenLifetime");
        leaseHeartbeatInterval = requirePositive(
                leaseHeartbeatInterval, "leaseHeartbeatInterval");
        if (recoveryCandidateLimit < 1 || recoveryCandidateLimit > 1_000) {
            throw new IllegalArgumentException(
                    "recoveryCandidateLimit must be between 1 and 1000");
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return required;
    }
}
