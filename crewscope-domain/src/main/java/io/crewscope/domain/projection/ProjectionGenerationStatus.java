package io.crewscope.domain.projection;

/** Lifecycle state of a persisted projection generation. */
public enum ProjectionGenerationStatus {
    BUILDING,
    VALIDATING,
    ACTIVE,
    RETIRED,
    FAILED,
    CANCELLED;

    public boolean acceptsWrites() {
        return this == BUILDING || this == VALIDATING || this == ACTIVE;
    }

    public boolean shadow() {
        return this == BUILDING || this == VALIDATING;
    }

    public boolean terminal() {
        return this == RETIRED || this == FAILED || this == CANCELLED;
    }
}
