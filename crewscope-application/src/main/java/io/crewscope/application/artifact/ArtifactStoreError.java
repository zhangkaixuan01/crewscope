package io.crewscope.application.artifact;

/** Stable failure categories shared by Filesystem and object-storage adapters. */
public enum ArtifactStoreError {
    INTEGRITY_VIOLATION,
    CONFLICT,
    ACCESS_DENIED,
    STORAGE_FAILURE
}
