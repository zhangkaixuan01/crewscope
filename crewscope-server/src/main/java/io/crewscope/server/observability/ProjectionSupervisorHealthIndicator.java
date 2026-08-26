package io.crewscope.server.observability;

import io.crewscope.infrastructure.event.projection.ProjectionSupervisor;
import io.crewscope.infrastructure.event.projection.ProjectionSupervisorSummary;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Actuator-safe Projection Supervisor health without tenant, target or exception cardinality. */
@Component
@ConditionalOnBean(ProjectionSupervisor.class)
public final class ProjectionSupervisorHealthIndicator implements HealthIndicator {

    private final ProjectionSupervisor supervisor;

    public ProjectionSupervisorHealthIndicator(ProjectionSupervisor supervisor) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public Health health() {
        ProjectionSupervisorSummary summary = supervisor.summary();
        Health.Builder health = summary.expired() > 0 ? Health.down() : Health.up();
        return health
                .withDetail("running", summary.running())
                .withDetail("caughtUp", summary.caughtUp())
                .withDetail("interrupted", summary.interrupted())
                .withDetail("expired", summary.expired())
                .withDetail("pendingRecovery", summary.pendingRecovery())
                .withDetail("cleanupEligible", summary.cleanupEligible())
                .build();
    }
}
