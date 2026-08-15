package io.crewscope.server.observability;

import io.crewscope.infrastructure.runtime.TaskWorkerExecutionLoop;
import io.crewscope.infrastructure.runtime.TaskWorkerLoopHealth;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Actuator projection for the local Worker loop without tenant or execution identifiers. */
public final class TaskWorkerHealthIndicator implements HealthIndicator {

    private final TaskWorkerExecutionLoop workerLoop;

    public TaskWorkerHealthIndicator(TaskWorkerExecutionLoop workerLoop) {
        this.workerLoop = Objects.requireNonNull(workerLoop, "workerLoop");
    }

    @Override
    public Health health() {
        TaskWorkerLoopHealth state = workerLoop.health();
        Health.Builder builder = state.started() ? Health.up() : Health.outOfService();
        return builder
                .withDetail("acceptingClaims", state.acceptingClaims())
                .withDetail("activeExecutions", state.activeExecutions())
                .withDetail("reconciledExecutions", state.reconciledExecutions())
                .withDetail("lastFailureType", state.lastFailureType().orElse("NONE"))
                .build();
    }
}
