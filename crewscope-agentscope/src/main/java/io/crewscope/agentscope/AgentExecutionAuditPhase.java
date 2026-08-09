package io.crewscope.agentscope;

/** AgentScope interception phase represented by a safe platform audit record. */
public enum AgentExecutionAuditPhase {
    INVOCATION,
    MODEL_CALL,
    TOOL_EXECUTION
}
