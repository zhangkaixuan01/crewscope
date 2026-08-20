package io.crewscope.infrastructure.workspace.repository;

/** Stable safe errors for Coding Artifact publication and consumption. */
public enum CodingArtifactError {
    INVALID_CONTEXT,
    METADATA_MISMATCH,
    CONTENT_UNAVAILABLE,
    RANGE_NOT_SATISFIABLE,
    SIZE_LIMIT_EXCEEDED,
    TOO_MANY_CONCURRENT_READS,
    PUBLICATION_FAILED,
    LIFECYCLE_FAILED
}
