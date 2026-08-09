package io.crewscope.agentscope;

/** Server-controlled role of a model participating in one logical AgentScope call. */
public enum AgentModelRole {
    LOGICAL,
    PRIMARY,
    FALLBACK
}
