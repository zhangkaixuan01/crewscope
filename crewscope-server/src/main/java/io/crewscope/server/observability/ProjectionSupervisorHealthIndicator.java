package io.crewscope.server.observability;

import io.crewscope.infrastructure.event.projection.ProjectionSupervisor;
import io.crewscope.infrastructure.event.projection.ProjectionSupervisorSummary;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Actuator-safe Projection Supervisor health without tenant, target or exception cardinality. */
// The supervisor is a @Bean of a later-scanned infrastructure configuration class, so a
// presence condition here never saw it and the indicator silently never registered — even with
// the supervisor enabled (M9b-Q02 defect 4). The property gate that governs the supervisor
// itself carries the intent instead; enabling it without a resolvable supervisor fails fast.
@Component
@ConditionalOnProperty(
        prefix = "crewscope.projection.supervisor",
        name = "enabled",
        havingValue = "true")
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
