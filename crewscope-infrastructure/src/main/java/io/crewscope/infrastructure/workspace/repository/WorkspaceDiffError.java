package io.crewscope.infrastructure.workspace.repository;

/** Stable infrastructure failures exposed by the Workspace Diff boundary. */
public enum WorkspaceDiffError {
    INVALID_CONTEXT,
    INVALID_GIT_OUTPUT,
    PATH_OUTSIDE_POLICY,
    DIFF_LIMIT_EXCEEDED,
    WATCHER_FAILED,
    INVALID_CURSOR,
    REPLAY_UNAVAILABLE,
    ARTIFACT_PUBLICATION_FAILED,
    FINALIZATION_CONFLICT,
    COMMAND_FAILED
}
