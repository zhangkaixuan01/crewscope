package io.crewscope.server.observability;

import io.crewscope.server.config.application.KnowledgeVectorMigrationRunner;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator projection for the optional knowledge vector store (M10-I01a). Assembled only
 * when the operator opted in: an opted-in deployment whose vector chain did not land is
 * {@code DOWN} with the runner's actionable reason, never silently healthy.
 */
public final class KnowledgeVectorHealthIndicator implements HealthIndicator {

    private final KnowledgeVectorMigrationRunner runner;

    public KnowledgeVectorHealthIndicator(KnowledgeVectorMigrationRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public Health health() {
        if (runner.applied()) {
            return Health.up().withDetail("vectorMigration", "applied").build();
        }
        return Health.down()
                .withDetail("vectorMigration", "not-applied")
                .withDetail("reason", runner.skippedReason())
                .build();
    }
}
