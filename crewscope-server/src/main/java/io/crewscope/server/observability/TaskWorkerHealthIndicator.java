package io.crewscope.server.observability;

import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.infrastructure.runtime.RuntimeRegistryCoordinator;
import io.crewscope.infrastructure.runtime.RuntimeWorkerHealth;
import io.crewscope.infrastructure.runtime.TaskWorkerExecutionLoop;
import io.crewscope.infrastructure.runtime.TaskWorkerLoopHealth;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Actuator projection for the local Worker loop without tenant or execution identifiers. */
public final class TaskWorkerHealthIndicator implements HealthIndicator {

    private final TaskWorkerExecutionLoop workerLoop;
    private final RuntimeRegistryCoordinator registryCoordinator;

    public TaskWorkerHealthIndicator(
            TaskWorkerExecutionLoop workerLoop,
            RuntimeRegistryCoordinator registryCoordinator) {
        this.workerLoop = Objects.requireNonNull(workerLoop, "workerLoop");
        this.registryCoordinator = Objects.requireNonNull(
                registryCoordinator, "registryCoordinator");
    }

    @Override
    public Health health() {
        TaskWorkerLoopHealth state = workerLoop.health();
        RuntimeWorkerHealth durable = registryCoordinator.health();
        Health.Builder builder = status(state, durable);
        return builder
                .withDetail("acceptingClaims", state.acceptingClaims())
                .withDetail("activeExecutions", state.activeExecutions())
                .withDetail("reconciledExecutions", state.reconciledExecutions())
                .withDetail("lastFailureType", state.lastFailureType().orElse("NONE"))
                .withDetail("workerStatus", durable.status().name())
                .withDetail("heartbeatFresh", durable.heartbeatFresh())
                .withDetail("claimable", durable.claimable())
                .withDetail("reportedActiveExecutions", durable.activeExecutions())
                .withDetail("maxConcurrentExecutions", durable.maxConcurrentExecutions())
                .build();
    }

    private static Health.Builder status(
            TaskWorkerLoopHealth loop, RuntimeWorkerHealth durable) {
        if (!loop.started() || durable.status() == RuntimeWorkerStatus.DRAINING) {
            return Health.outOfService();
        }
        if (!durable.heartbeatFresh()
                || durable.status() == RuntimeWorkerStatus.DISABLED
                || durable.status() == RuntimeWorkerStatus.REGISTERED) {
            return Health.down();
        }
        return Health.up();
    }
}
