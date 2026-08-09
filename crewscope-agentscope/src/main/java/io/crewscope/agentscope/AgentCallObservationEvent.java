package io.crewscope.agentscope;

/** Stable model-call lifecycle events exposed to logs and low-cardinality metrics. */
public enum AgentCallObservationEvent {
    STARTED,
    RETRYING,
    FALLBACK_SELECTED,
    COMPLETED,
    FAILED,
    CANCELED
}
