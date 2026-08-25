package io.crewscope.domain.projection;

/** Durable supervisor status of one rebuild attempt. */
public enum ProjectionRebuildStatus {
    BUILDING,
    VALIDATING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
