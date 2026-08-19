package io.crewscope.infrastructure.workspace.repository;

/** Stable path-free failure categories for managed repository baseline Preflight. */
public enum RepositoryPreflightError {
    MANAGED_ROOT_INVALID,
    REPOSITORY_NOT_FOUND,
    PATH_ESCAPE,
    SYMLINK_REJECTED,
    OWNER_MISMATCH,
    NOT_BARE_REPOSITORY,
    BINDING_INACTIVE,
    BINDING_MISMATCH,
    REFERENCE_INVALID,
    BASELINE_MOVED,
    COMMIT_NOT_FOUND,
    COMMAND_FAILED
}
