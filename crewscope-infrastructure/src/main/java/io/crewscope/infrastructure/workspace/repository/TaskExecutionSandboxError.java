package io.crewscope.infrastructure.workspace.repository;

/** Stable, host-path-free classifications for TaskExecution Docker lifecycle failures. */
public enum TaskExecutionSandboxError {
    INVALID_CONFIGURATION,
    WORKSPACE_MISMATCH,
    POLICY_MISMATCH,
    OWNERSHIP_MISMATCH,
    LEASE_EXPIRED,
    SANDBOX_BUSY,
    CONTAINER_CONFLICT,
    CONTAINER_CORRUPT,
    COMMAND_FAILED,
    CLEANUP_FAILED
}
