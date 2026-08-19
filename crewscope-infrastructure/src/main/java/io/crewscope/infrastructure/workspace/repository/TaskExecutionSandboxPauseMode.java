package io.crewscope.infrastructure.workspace.repository;

/** Container handling policy while a TaskExecution is paused. */
public enum TaskExecutionSandboxPauseMode {
    STOP,
    KEEP_RUNNING
}
