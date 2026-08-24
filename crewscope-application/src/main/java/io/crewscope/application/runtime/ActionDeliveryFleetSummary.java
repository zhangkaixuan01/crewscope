package io.crewscope.application.runtime;

import io.crewscope.application.action.ActionReconciliationHealth;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;

/** Member-safe, low-cardinality Action delivery health without internal queue coordinates. */
public record ActionDeliveryFleetSummary(
        String health,
        long running,
        long unknown,
        long reconciling,
        long manualReview,
        long oldestUnresolvedAgeSeconds,
        boolean stale) {

    public ActionDeliveryFleetSummary {
        health = Objects.requireNonNull(health, "health");
        if (running < 0 || unknown < 0 || reconciling < 0 || manualReview < 0
                || oldestUnresolvedAgeSeconds < 0) {
            throw new IllegalArgumentException("Action delivery fleet counters must not be negative");
        }
    }

    public static ActionDeliveryFleetSummary from(
            ActionReconciliationHealth state,
            UtcTimestamp observedAt,
            Duration staleThreshold) {
        ActionReconciliationHealth value = Objects.requireNonNull(state, "state");
        long age = value.oldestUnresolvedAt()
                .map(time -> Math.max(0L, Duration.between(
                                time.value(), observedAt.value())
                        .toSeconds()))
                .orElse(0L);
        boolean stale = age >= Objects.requireNonNull(staleThreshold, "staleThreshold").toSeconds();
        String health = stale || value.manualReview() > 0
                ? "ATTENTION_REQUIRED"
                : value.unresolved() > 0 ? "DEGRADED" : "HEALTHY";
        return new ActionDeliveryFleetSummary(
                health,
                value.running(),
                value.unknown(),
                value.reconciling(),
                value.manualReview(),
                age,
                stale);
    }
}
