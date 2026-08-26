package io.crewscope.application.operations;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Safe aggregate facts read from persistence before deployment thresholds are applied. */
public record OperationsComponentObservation(
        OperationsHealthComponent component,
        long backlog,
        long inFlight,
        long failures,
        long affected,
        Optional<UtcTimestamp> oldestOutstandingAt,
        boolean unavailable) {

    public OperationsComponentObservation {
        component = Objects.requireNonNull(component, "component");
        oldestOutstandingAt = Objects.requireNonNull(
                oldestOutstandingAt, "oldestOutstandingAt");
        if (backlog < 0 || inFlight < 0 || failures < 0 || affected < 0) {
            throw new IllegalArgumentException("operations component counts must not be negative");
        }
        if (backlog == 0 && oldestOutstandingAt.isPresent()) {
            throw new IllegalArgumentException(
                    "an empty backlog cannot have an oldest outstanding timestamp");
        }
        if (backlog > 0 && oldestOutstandingAt.isEmpty()) {
            throw new IllegalArgumentException(
                    "a non-empty backlog requires an oldest outstanding timestamp");
        }
    }
}
