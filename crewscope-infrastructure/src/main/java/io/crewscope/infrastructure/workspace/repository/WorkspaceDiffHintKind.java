package io.crewscope.infrastructure.workspace.repository;

/** WatchService hints are coalesced; only exceptional states request an explicit full reset. */
public enum WorkspaceDiffHintKind {
    CHANGED,
    FULL_RECONCILE
}
