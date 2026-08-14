package io.crewscope.domain.task;

/** Stable capability vocabulary used by plans and policy snapshots without Runtime dependencies. */
public enum ExecutionCapability {
    SESSION_RESUME,
    SESSION_FORK,
    PLAN,
    STRUCTURED_OUTPUT,
    TOOL_APPROVAL,
    CONTEXT_USAGE,
    SANDBOX,
    WORKTREE,
    MULTI_REPOSITORY
}
