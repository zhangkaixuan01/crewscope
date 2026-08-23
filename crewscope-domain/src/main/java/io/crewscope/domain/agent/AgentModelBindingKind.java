package io.crewscope.domain.agent;

/** Resolution mode of one execution-scope model binding. */
public enum AgentModelBindingKind {
    DIRECT,
    INHERIT_TEAM_DEFAULT,
    ORCHESTRATION_ONLY
}
