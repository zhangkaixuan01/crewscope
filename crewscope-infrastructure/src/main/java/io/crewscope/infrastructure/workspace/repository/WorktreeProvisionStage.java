package io.crewscope.infrastructure.workspace.repository;

/** Package-internal deterministic fault-injection points for lifecycle integration tests. */
enum WorktreeProvisionStage {
    BEFORE_WORKTREE_ADD,
    AFTER_WORKTREE_ADD,
    AFTER_ARCHIVE_RESTORE,
    AFTER_ARCHIVE_REFERENCE,
    BEFORE_WORKTREE_REMOVE
}
