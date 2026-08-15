package io.crewscope.infrastructure.runtime;

import java.time.Duration;
import java.util.Objects;

/** Bounded scheduling and graceful-shutdown policy for one JVM Task Worker loop. */
public record TaskWorkerLoopSpec(
        int maximumConcurrentExecutions,
        int claimBatchSize,
        Duration pollInterval,
        Duration gracefulShutdownTimeout) {

    public TaskWorkerLoopSpec {
        if (maximumConcurrentExecutions < 1 || maximumConcurrentExecutions > 10_000) {
            throw new IllegalArgumentException(
                    "maximumConcurrentExecutions must be between 1 and 10000");
        }
        if (claimBatchSize < 1 || claimBatchSize > maximumConcurrentExecutions) {
            throw new IllegalArgumentException(
                    "claimBatchSize must be between 1 and maximumConcurrentExecutions");
        }
        pollInterval = requireDuration(pollInterval, "pollInterval", Duration.ofMillis(50),
                Duration.ofMinutes(1));
        gracefulShutdownTimeout = requireDuration(
                gracefulShutdownTimeout,
                "gracefulShutdownTimeout",
                Duration.ofSeconds(1),
                Duration.ofMinutes(15));
    }

    private static Duration requireDuration(
            Duration value, String field, Duration minimum, Duration maximum) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " must be between " + minimum + " and " + maximum);
        }
        return required;
    }
}
