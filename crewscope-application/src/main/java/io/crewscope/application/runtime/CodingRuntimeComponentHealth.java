package io.crewscope.application.runtime;

/** Low-cardinality health state for one Coding Runtime resource component. */
public enum CodingRuntimeComponentHealth {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE
}
