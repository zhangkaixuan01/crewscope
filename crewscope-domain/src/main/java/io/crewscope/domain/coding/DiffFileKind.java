package io.crewscope.domain.coding;

/** Git authority change kinds exposed by a DiffManifest. */
public enum DiffFileKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    TYPE_CHANGED
}
