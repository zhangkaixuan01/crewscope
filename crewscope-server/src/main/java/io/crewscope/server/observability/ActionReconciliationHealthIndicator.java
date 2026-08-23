package io.crewscope.server.observability;

import io.crewscope.application.action.ActionDispatchRepository;
import io.crewscope.application.action.ActionReconciliationHealth;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Low-cardinality Actuator health for UNKNOWN recovery and the human queue. */
public final class ActionReconciliationHealthIndicator implements HealthIndicator {

    private final ActionDispatchRepository dispatches;
    private final TimeProvider timeProvider;
    private final Duration maximumUnknownAge;

    public ActionReconciliationHealthIndicator(
            ActionDispatchRepository dispatches,
            TimeProvider timeProvider,
            Duration maximumUnknownAge) {
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.maximumUnknownAge = Objects.requireNonNull(maximumUnknownAge, "maximumUnknownAge");
    }

    @Override
    public Health health() {
        ActionReconciliationHealth state = dispatches.reconciliationHealth();
        long oldestAgeSeconds = state.oldestUnresolvedAt()
                .map(value -> Math.max(0L, Duration.between(
                                value.value(), timeProvider.now().value())
                        .toSeconds()))
                .orElse(0L);
        boolean stale = oldestAgeSeconds >= maximumUnknownAge.toSeconds();
        Health.Builder status = stale || state.manualReview() > 0
                ? Health.outOfService()
                : Health.up();
        return status
                .withDetail("running", state.running())
                .withDetail("unknown", state.unknown())
                .withDetail("reconciling", state.reconciling())
                .withDetail("manualReview", state.manualReview())
                .withDetail("oldestUnresolvedAgeSeconds", oldestAgeSeconds)
                .withDetail("stale", stale)
                .build();
    }
}
