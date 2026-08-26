package io.crewscope.infrastructure.event.projection;

/** Outcome of one event attempt against one projection Generation. */
public enum ProjectionConsumptionResult {
    APPLIED,
    STALE,
    DUPLICATE,
    LEASE_REJECTED
}
