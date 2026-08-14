package io.crewscope.domain.runtime;

/** Routable capabilities published only after the production boundary is wired. */
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
