package io.crewscope.application.operations;

import java.util.Objects;

/** Identifier-free component summary safe for every active Team member. */
public record OperationsComponentSummary(
        OperationsHealthComponent component,
        OperationsHealthLevel health,
        long backlog,
        long inFlight,
        long failures,
        long affected,
        long oldestOutstandingAgeSeconds,
        boolean stale) {

    public OperationsComponentSummary {
        component = Objects.requireNonNull(component, "component");
        health = Objects.requireNonNull(health, "health");
        if (backlog < 0
                || inFlight < 0
                || failures < 0
                || affected < 0
                || oldestOutstandingAgeSeconds < 0) {
            throw new IllegalArgumentException("operations summary counts must not be negative");
        }
    }
}
