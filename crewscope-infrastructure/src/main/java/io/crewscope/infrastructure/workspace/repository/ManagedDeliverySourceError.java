package io.crewscope.infrastructure.workspace.repository;

/** Stable source-side validation stage for one confirmed delivery. */
public enum ManagedDeliverySourceError {
    BASELINE_UNAVAILABLE,
    DELIVERY_UNAVAILABLE,
    MIRROR_IMPORT_FAILED
}
