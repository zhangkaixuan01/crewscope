package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ExecutionLease;
import java.time.Duration;
import java.util.Objects;

/** Validated deployment policy for one stable Worker's Claim Scheduler. */
public record TaskClaimSchedulerSpec(
        OrganizationId organizationId,
        RuntimeEnvironment environment,
        String runtimeKey,
        String workerStableKey,
        Principal actor,
        Duration workerHeartbeatTimeout,
        Duration prepareLeaseDuration,
        int teamConcurrentLimit,
        int runtimeConcurrentLimit,
        int maximumBatchSize,
        int maximumScanSize) {

    public TaskClaimSchedulerSpec {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(environment, "environment");
        runtimeKey = requireText(runtimeKey, "runtimeKey");
        workerStableKey = requireText(workerStableKey, "workerStableKey");
        Objects.requireNonNull(actor, "actor");
        workerHeartbeatTimeout = Objects.requireNonNull(
                workerHeartbeatTimeout, "workerHeartbeatTimeout");
        prepareLeaseDuration = Objects.requireNonNull(
                prepareLeaseDuration, "prepareLeaseDuration");
        if (workerHeartbeatTimeout.compareTo(Duration.ofSeconds(5)) < 0
                || workerHeartbeatTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("workerHeartbeatTimeout must be between 5s and 10m");
        }
        if (prepareLeaseDuration.compareTo(ExecutionLease.MIN_LEASE_DURATION) < 0
                || prepareLeaseDuration.compareTo(ExecutionLease.MAX_PREPARE_LEASE_DURATION) > 0) {
            throw new IllegalArgumentException("prepareLeaseDuration is outside PREPARE Lease bounds");
        }
        if (teamConcurrentLimit < 1 || runtimeConcurrentLimit < 1) {
            throw new IllegalArgumentException("scheduler concurrency limits must be positive");
        }
        if (maximumBatchSize < 1 || maximumBatchSize > 100) {
            throw new IllegalArgumentException("maximumBatchSize must be between 1 and 100");
        }
        if (maximumScanSize < maximumBatchSize || maximumScanSize > 200) {
            throw new IllegalArgumentException(
                    "maximumScanSize must be between maximumBatchSize and 200");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
