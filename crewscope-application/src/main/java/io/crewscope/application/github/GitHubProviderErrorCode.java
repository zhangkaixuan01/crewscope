package io.crewscope.application.github;

/** Stable safe failures returned by the GitHub infrastructure boundary. */
public enum GitHubProviderErrorCode {
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    RATE_LIMITED,
    RESOURCE_UNAVAILABLE,
    CONFLICT,
    VALIDATION_FAILED,
    PROVIDER_UNAVAILABLE,
    CONNECTION_UNAVAILABLE,
    GRANT_UNAVAILABLE,
    CREDENTIAL_UNAVAILABLE,
    IDENTITY_MISMATCH,
    REPOSITORY_BLOCKED,
    REPOSITORY_STALE,
    DEFAULT_BRANCH_MISMATCH
}
