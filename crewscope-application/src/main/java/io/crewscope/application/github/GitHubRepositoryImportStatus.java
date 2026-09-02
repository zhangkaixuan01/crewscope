package io.crewscope.application.github;

/** Durable lifecycle states for one bounded GitHub repository import attempt. */
public enum GitHubRepositoryImportStatus {
    REQUESTED,
    PREFLIGHTING,
    IMPORTING,
    READY,
    FAILED,
    CANCELLED
}
