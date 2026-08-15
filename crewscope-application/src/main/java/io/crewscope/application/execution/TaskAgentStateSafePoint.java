package io.crewscope.application.execution;

/** AgentScope boundaries at which no Tool is partially executing and full state may be copied. */
public enum TaskAgentStateSafePoint {
    PERIODIC,
    CALL_COMPLETED,
    INTERRUPTED,
    PAUSED,
    SHUTDOWN
}
