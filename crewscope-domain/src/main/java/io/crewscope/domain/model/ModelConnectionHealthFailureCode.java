package io.crewscope.domain.model;

/** Stable non-secret reason for a failed model connection verification. */
public enum ModelConnectionHealthFailureCode {
    AUTHENTICATION_FAILED,
    ENDPOINT_UNREACHABLE,
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_REJECTED,
    POLICY_REJECTED
}
