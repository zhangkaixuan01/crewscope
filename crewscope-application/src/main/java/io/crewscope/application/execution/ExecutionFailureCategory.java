package io.crewscope.application.execution;

/** Stable safe classification for runtime failures exposed to application consumers. */
public enum ExecutionFailureCategory {
    VALIDATION,
    AUTHORIZATION,
    CAPABILITY_UNAVAILABLE,
    MODEL_RATE_LIMITED,
    MODEL_UNAVAILABLE,
    MODEL_OUTPUT_INVALID,
    TOOL_FAILED,
    STATE_UNAVAILABLE,
    TIMEOUT,
    INTERNAL
}
