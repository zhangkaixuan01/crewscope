package io.crewscope.server.api;

import io.crewscope.application.operations.OperationsComponentSummary;

/** One identifier-free operations component summary row of the member health view. */
public record ComponentHealthResponse(
        String component,
        String health,
        long backlog,
        long inFlight,
        long failures,
        long affected,
        long oldestOutstandingAgeSeconds,
        boolean stale) {

    static ComponentHealthResponse from(OperationsComponentSummary value) {
        return new ComponentHealthResponse(
                value.component().name(),
                value.health().name(),
                value.backlog(),
                value.inFlight(),
                value.failures(),
                value.affected(),
                value.oldestOutstandingAgeSeconds(),
                value.stale());
    }
}
