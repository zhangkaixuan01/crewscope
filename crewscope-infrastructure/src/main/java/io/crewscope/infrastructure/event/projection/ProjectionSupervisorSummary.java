package io.crewscope.infrastructure.event.projection;

/** Low-cardinality Actuator-safe view of Projection Supervisor state. */
public record ProjectionSupervisorSummary(
        long running,
        long caughtUp,
        long interrupted,
        long expired,
        long pendingRecovery,
        long cleanupEligible) {

    public ProjectionSupervisorSummary {
        if (running < 0 || caughtUp < 0 || interrupted < 0 || expired < 0
                || pendingRecovery < 0 || cleanupEligible < 0) {
            throw new IllegalArgumentException("Projection Supervisor counters must not be negative");
        }
    }
}
