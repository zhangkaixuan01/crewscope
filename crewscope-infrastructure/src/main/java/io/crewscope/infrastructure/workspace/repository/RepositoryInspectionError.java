package io.crewscope.infrastructure.workspace.repository;

/** Stable failure classifications exposed by the controlled repository inspection boundary. */
public enum RepositoryInspectionError {
    INVALID_REQUEST,
    INVALID_CONTEXT,
    INVALID_PATH,
    PATH_NOT_ALLOWED,
    SENSITIVE_PATH,
    SYMBOLIC_LINK,
    BINARY_FILE,
    TRAVERSAL_LIMIT,
    FILESYSTEM_FAILED,
    GIT_FAILED
}
