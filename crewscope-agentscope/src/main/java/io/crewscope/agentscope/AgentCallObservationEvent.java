package io.crewscope.agentscope;

/**
 * Stable model-call lifecycle events exposed to logs and low-cardinality metrics. The two
 * M12-I03 latency probes are non-terminal: {@code FIRST_CHUNK} fires once per model call
 * (model-call initiation to the first response event) and {@code APPLICATION_OVERHEAD}
 * once per invocation (acceptance to the first model-call initiation).
 */
public enum AgentCallObservationEvent {
    STARTED,
    FIRST_CHUNK,
    APPLICATION_OVERHEAD,
    RETRYING,
    FALLBACK_SELECTED,
    COMPLETED,
    FAILED,
    CANCELED
}
