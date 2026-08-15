package io.crewscope.infrastructure.runtime;

import java.util.Objects;
import java.util.Optional;

/** Safe process-local health projection; execution identities and exception messages stay private. */
public record TaskWorkerLoopHealth(
        boolean started,
        boolean acceptingClaims,
        int activeExecutions,
        int reconciledExecutions,
        Optional<String> lastFailureType) {

    public TaskWorkerLoopHealth {
        if (activeExecutions < 0 || reconciledExecutions < 0) {
            throw new IllegalArgumentException("Worker health counters must not be negative");
        }
        lastFailureType = Objects.requireNonNull(lastFailureType, "lastFailureType");
    }
}
