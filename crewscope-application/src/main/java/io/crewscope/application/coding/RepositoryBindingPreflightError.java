package io.crewscope.application.coding;

/** Stable, path-free Repository Preflight failure categories exposed to server adapters. */
public enum RepositoryBindingPreflightError {
    SERVICE_UNAVAILABLE,
    REPOSITORY_NOT_FOUND,
    REPOSITORY_INVALID,
    REFERENCE_INVALID,
    COMMAND_FAILED
}
