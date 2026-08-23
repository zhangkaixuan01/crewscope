package io.crewscope.application.github;

/** Stable result of an idempotent GitHub branch delivery. */
public enum GitHubPushOutcome {
    PUSHED,
    ALREADY_PRESENT,
    RECOVERED_AFTER_UNKNOWN
}
