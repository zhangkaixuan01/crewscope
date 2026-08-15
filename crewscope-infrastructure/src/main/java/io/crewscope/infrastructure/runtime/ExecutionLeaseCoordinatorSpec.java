package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ExecutionLease;
import java.time.Duration;
import java.util.Objects;

/** Validated phase TTL, jitter and Sweeper policy for one Runtime deployment. */
public record ExecutionLeaseCoordinatorSpec(
        OrganizationId organizationId,
        RuntimeEnvironment environment,
        Principal actor,
        Duration prepareLeaseDuration,
        Duration runLeaseDuration,
        Duration heartbeatInterval,
        Duration heartbeatJitterTolerance,
        Duration sweeperInterval,
        int maximumSweepSize) {

    public ExecutionLeaseCoordinatorSpec {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(actor, "actor");
        prepareLeaseDuration = requireLeaseDuration(
                prepareLeaseDuration,
                ExecutionLease.MAX_PREPARE_LEASE_DURATION,
                "prepareLeaseDuration");
        runLeaseDuration = requireLeaseDuration(
                runLeaseDuration,
                ExecutionLease.MAX_RUN_LEASE_DURATION,
                "runLeaseDuration");
        heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        heartbeatJitterTolerance = Objects.requireNonNull(
                heartbeatJitterTolerance, "heartbeatJitterTolerance");
        if (heartbeatJitterTolerance.isNegative()) {
            throw new IllegalArgumentException("heartbeatJitterTolerance must not be negative");
        }
        Duration renewalWindow = heartbeatInterval.plus(heartbeatJitterTolerance);
        if (renewalWindow.compareTo(prepareLeaseDuration) >= 0
                || renewalWindow.compareTo(runLeaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "heartbeat interval plus jitter tolerance must be shorter than every phase TTL");
        }
        sweeperInterval = requirePositive(sweeperInterval, "sweeperInterval");
        if (maximumSweepSize < 1 || maximumSweepSize > 1000) {
            throw new IllegalArgumentException("maximumSweepSize must be between 1 and 1000");
        }
    }

    public Duration durationFor(io.crewscope.domain.task.ExecutionLeasePhase phase) {
        return phase == io.crewscope.domain.task.ExecutionLeasePhase.PREPARE
                ? prepareLeaseDuration
                : runLeaseDuration;
    }

    private static Duration requireLeaseDuration(
            Duration value, Duration maximum, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.compareTo(ExecutionLease.MIN_LEASE_DURATION) < 0
                || required.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " is outside Lease bounds");
        }
        return required;
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return required;
    }
}
