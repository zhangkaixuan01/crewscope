package io.crewscope.application.github;

/** Stable result classification for one idempotent Draft PR operation. */
public enum GitHubDraftPullRequestOutcome {
    CREATED,
    ALREADY_PRESENT,
    RECOVERED_AFTER_UNKNOWN
}
