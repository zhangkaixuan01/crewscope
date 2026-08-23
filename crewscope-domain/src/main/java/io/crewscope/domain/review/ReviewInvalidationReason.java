package io.crewscope.domain.review;

/** Stable authority coordinate that made a historical ReviewRequest stale. */
public enum ReviewInvalidationReason {
    SUBJECT_CHANGED,
    DIFF_CHANGED,
    TEST_EVIDENCE_CHANGED,
    REVIEWER_CONFIGURATION_CHANGED,
    POLICY_CHANGED,
    CONTEXT_CHANGED
}
