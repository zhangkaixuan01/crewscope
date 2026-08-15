package io.crewscope.application.runtime;

/** Member-safe aggregate health of one Runtime environment. */
public enum RuntimeFleetHealth {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE
}
