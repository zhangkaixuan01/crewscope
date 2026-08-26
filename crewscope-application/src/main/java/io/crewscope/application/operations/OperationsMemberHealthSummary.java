package io.crewscope.application.operations;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Organization-wide, identifier-free health summary visible to an active Team member. */
public record OperationsMemberHealthSummary(
        UtcTimestamp observedAt,
        OperationsHealthLevel health,
        List<OperationsComponentSummary> components) {

    public OperationsMemberHealthSummary {
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        health = Objects.requireNonNull(health, "health");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        EnumSet<OperationsHealthComponent> found = EnumSet.noneOf(OperationsHealthComponent.class);
        for (OperationsComponentSummary component : components) {
            if (!found.add(component.component())) {
                throw new IllegalArgumentException("operations summary contains a duplicate component");
            }
        }
        if (found.size() != OperationsHealthComponent.values().length) {
            throw new IllegalArgumentException("operations summary must contain every component");
        }
        OperationsHealthLevel derived = components.stream()
                .map(OperationsComponentSummary::health)
                .reduce(OperationsHealthLevel.HEALTHY, OperationsHealthLevel::worst);
        if (health != derived) {
            throw new IllegalArgumentException("overall health must equal the worst component");
        }
    }

    /** Provides a bounded source for M6-I08 Prometheus gauges without identity labels. */
    public List<OperationsHealthMetric> metrics() {
        return components.stream()
                .map(component -> new OperationsHealthMetric(
                        component.component(),
                        component.health(),
                        component.backlog(),
                        component.failures()))
                .toList();
    }
}
