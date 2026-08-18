package io.crewscope.domain.coding;

/** Durable lifecycle of a managed Coding Worktree and its local delivery anchor. */
public enum ExecutionWorkspaceStatus {
    PENDING,
    PROVISIONING,
    READY,
    ACTIVE,
    FINALIZING,
    COMPLETED,
    RECOVERING,
    FAILED,
    ARCHIVED;

    public boolean isRetentionTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean isImmutableTerminal() {
        return this == ARCHIVED;
    }
}
