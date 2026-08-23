package io.crewscope.domain.model;

/** Provider-side handling of request and response data after one model call. */
public enum ModelDataRetentionMode {
    NONE,
    TIME_BOUND,
    PROVIDER_MANAGED
}
