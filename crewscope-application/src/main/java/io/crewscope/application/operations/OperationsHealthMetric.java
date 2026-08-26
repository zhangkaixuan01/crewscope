package io.crewscope.application.operations;

import java.util.Objects;

/** Metric sample whose only labels are the two bounded enums in this record. */
public record OperationsHealthMetric(
        OperationsHealthComponent component,
        OperationsHealthLevel health,
        long backlog,
        long failures) {

    public OperationsHealthMetric {
        component = Objects.requireNonNull(component, "component");
        health = Objects.requireNonNull(health, "health");
        if (backlog < 0 || failures < 0) {
            throw new IllegalArgumentException("metric values must not be negative");
        }
    }
}
