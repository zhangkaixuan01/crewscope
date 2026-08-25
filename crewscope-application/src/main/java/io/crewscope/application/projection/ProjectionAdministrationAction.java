package io.crewscope.application.projection;

/** Stable low-cardinality action names used by command receipts, DomainEvent and Audit. */
public enum ProjectionAdministrationAction {
    START_REBUILD,
    RETRY_REBUILD,
    VALIDATE_GENERATION,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    SWITCH_GENERATION,
    CANCEL_REBUILD,
    FAIL_REBUILD
}
