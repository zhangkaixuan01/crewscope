package io.crewscope.infrastructure.workspace.repository;

/** Stable, path-free classifications for managed Worktree lifecycle failures. */
public enum WorktreeOperationError {
    MANAGED_ROOT_INVALID,
    WORKSPACE_BUSY,
    PATH_ESCAPE,
    PATH_SYMLINK_ESCAPE,
    UNOWNED_PATH_RESIDUE,
    BRANCH_CONFLICT,
    NOT_PROVISIONED,
    CORRUPT_HEAD,
    CORRUPT_BRANCH,
    CORRUPT_GIT_POINTER,
    POLICY_MISMATCH,
    WORKSPACE_MISMATCH,
    ARCHIVE_CONFLICT,
    ROLLBACK_FAILED,
    CLEANUP_FAILED,
    COMMAND_FAILED
}
