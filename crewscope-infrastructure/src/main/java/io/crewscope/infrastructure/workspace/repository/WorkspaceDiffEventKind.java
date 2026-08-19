package io.crewscope.infrastructure.workspace.repository;

/** RESET replaces the complete projection; DELTA applies only to its direct predecessor. */
public enum WorkspaceDiffEventKind {
    RESET,
    DELTA
}
