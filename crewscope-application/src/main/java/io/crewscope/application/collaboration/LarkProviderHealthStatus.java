package io.crewscope.application.collaboration;

/** Closed, non-sensitive outcome vocabulary for one live Lark Provider check. */
public enum LarkProviderHealthStatus {
    HEALTHY,
    AUTHORIZATION_UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    RESOURCE_UNAVAILABLE,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    INVALID_RESPONSE,
    IDENTITY_MISMATCH,
    CONNECTION_UNAVAILABLE,
    CREDENTIAL_UNAVAILABLE,
    CANCELLED
}
