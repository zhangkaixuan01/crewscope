package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Safe aggregate returned to every active Team member without infrastructure identities. */
public record RuntimeFleetSummary(
        RuntimeEnvironment environment,
        UtcTimestamp observedAt,
        RuntimeFleetHealth health,
        int runtimeCount,
        int workerCount,
        int activeWorkerCount,
        int staleWorkerCount,
        int drainingWorkerCount,
        RuntimeCapacitySummary capacity,
        int waitingRuntimeExecutions,
        Map<RuntimeWaitCause, Long> waitingCauses,
        Optional<CodingWorkspaceFleetSummary> codingWorkspaces,
        Optional<ActionDeliveryFleetSummary> actionDelivery) {

    public RuntimeFleetSummary(
            RuntimeEnvironment environment,
            UtcTimestamp observedAt,
            RuntimeFleetHealth health,
            int runtimeCount,
            int workerCount,
            int activeWorkerCount,
            int staleWorkerCount,
            int drainingWorkerCount,
            RuntimeCapacitySummary capacity,
            int waitingRuntimeExecutions,
            Map<RuntimeWaitCause, Long> waitingCauses,
            Optional<CodingWorkspaceFleetSummary> codingWorkspaces) {
        this(
                environment, observedAt, health, runtimeCount, workerCount,
                activeWorkerCount, staleWorkerCount, drainingWorkerCount, capacity,
                waitingRuntimeExecutions, waitingCauses, codingWorkspaces, Optional.empty());
    }

    public RuntimeFleetSummary {
        environment = Objects.requireNonNull(environment, "environment");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        health = Objects.requireNonNull(health, "health");
        capacity = Objects.requireNonNull(capacity, "capacity");
        waitingCauses = Map.copyOf(Objects.requireNonNull(waitingCauses, "waitingCauses"));
        codingWorkspaces = Objects.requireNonNull(codingWorkspaces, "codingWorkspaces");
        actionDelivery = Objects.requireNonNull(actionDelivery, "actionDelivery");
        if (runtimeCount < 0
                || workerCount < 0
                || activeWorkerCount < 0
                || staleWorkerCount < 0
                || drainingWorkerCount < 0
                || waitingRuntimeExecutions < 0
                || waitingCauses.values().stream().anyMatch(value -> value == null || value < 1)
                || waitingCauses.values().stream().mapToLong(Long::longValue).sum()
                        != waitingRuntimeExecutions) {
            throw new IllegalArgumentException("runtime fleet counts must be non-negative and closed");
        }
    }
}
