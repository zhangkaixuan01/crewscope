package io.crewscope.application.github;

/** Stable safe failures for the GitHub Git delivery boundary. */
public enum GitHubPushErrorCode {
    AUTHORITY_STALE,
    MIRROR_UNAVAILABLE,
    BASELINE_MISMATCH,
    DELIVERY_HEAD_MISMATCH,
    REMOTE_HEAD_CONFLICT,
    NON_FAST_FORWARD,
    PROTECTED_BRANCH,
    PUSH_REJECTED,
    UNKNOWN
}
