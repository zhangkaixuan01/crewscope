package io.crewscope.application.action;

/** Bounded low-cardinality outcome of one reconciliation claim. */
public enum ActionReconciliationOutcome {
    SUCCEEDED,
    INCONCLUSIVE,
    MANUAL_REVIEW,
    CLAIM_LOST,
    FAILED
}
