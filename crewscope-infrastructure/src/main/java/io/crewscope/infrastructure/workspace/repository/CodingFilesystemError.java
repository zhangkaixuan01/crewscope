package io.crewscope.infrastructure.workspace.repository;

/** Stable failures exposed by the controlled Coding filesystem mutation boundary. */
public enum CodingFilesystemError {
    INVALID_REQUEST,
    INVALID_CONTEXT,
    INVALID_PATH,
    PATH_NOT_ALLOWED,
    SENSITIVE_PATH,
    SYMBOLIC_LINK,
    CASE_COLLISION,
    FILE_NOT_FOUND,
    PATH_EXISTS,
    NOT_REGULAR_FILE,
    BINARY_FILE,
    STALE_CONTENT,
    PATCH_INVALID,
    BUDGET_EXCEEDED,
    TOCTOU_DETECTED,
    FILESYSTEM_FAILED
}
