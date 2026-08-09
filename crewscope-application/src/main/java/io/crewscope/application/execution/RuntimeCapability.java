package io.crewscope.application.execution;

/** Routable runtime capabilities that become available only after CrewScope wires the boundary. */
public enum RuntimeCapability {
    CONVERSATION,
    STREAMING,
    STRUCTURED_OUTPUT,
    INTERRUPT_RESUME,
    CANCEL,
    SESSION_STATE,
    PLAN,
    SANDBOX,
    WORKTREE,
    MULTI_REPOSITORY,
    SUBAGENT,
    MEMORY,
    EXTERNAL_TOOL,
    DISTRIBUTED_STATE
}
