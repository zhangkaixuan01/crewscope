package io.crewscope.infrastructure.workspace.git;

/** Stable failure categories exposed by the host Git execution boundary. */
public enum GitCommandError {
    NOT_A_REPOSITORY,
    INVALID_REFERENCE,
    CONFLICT,
    TIMEOUT,
    OUTPUT_LIMIT,
    COMMAND_FAILED
}
