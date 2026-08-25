package io.crewscope.domain.projection;

/** Stable low-cardinality projection lifecycle fact type. */
public enum ProjectionLifecycleEventType {
    REBUILD_STARTED,
    REBUILD_RETRIED,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    GENERATION_SWITCHED,
    REBUILD_CANCELLED,
    REBUILD_FAILED
}
