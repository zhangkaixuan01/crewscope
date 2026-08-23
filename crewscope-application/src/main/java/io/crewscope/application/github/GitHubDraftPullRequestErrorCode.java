package io.crewscope.application.github;

/** Stable safe error taxonomy for Draft PR delivery. */
public enum GitHubDraftPullRequestErrorCode {
    AUTHORITY_STALE,
    REMOTE_HEAD_CONFLICT,
    PULL_REQUEST_CONFLICT,
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    RATE_LIMITED,
    RESOURCE_UNAVAILABLE,
    VALIDATION_FAILED,
    PROVIDER_UNAVAILABLE,
    UNKNOWN
}
