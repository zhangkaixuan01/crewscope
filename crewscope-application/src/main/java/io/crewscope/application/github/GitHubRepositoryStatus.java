package io.crewscope.application.github;

/** Catalog lifecycle and delivery eligibility. */
public enum GitHubRepositoryStatus {
    DISCOVERED,
    DELIVERABLE,
    BLOCKED,
    STALE
}
