package io.crewscope.domain.task;

/** Product-level reason an AgentRun stopped at a resumable safe point. */
public enum AgentInterruptKind {
    CLARIFICATION,
    PERMISSION,
    APPROVAL,
    PAUSE
}
