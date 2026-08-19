package io.crewscope.application.artifact;

/** Stable failure categories shared by Filesystem and object-storage adapters. */
public enum ArtifactStoreError {
    INTEGRITY_VIOLATION,
    RANGE_NOT_SATISFIABLE,
    CONFLICT,
    ACCESS_DENIED,
    STORAGE_FAILURE
}
