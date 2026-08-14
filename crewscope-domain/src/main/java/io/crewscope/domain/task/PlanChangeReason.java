package io.crewscope.domain.task;

/** Reason for publishing a new PlanVersion. */
public enum PlanChangeReason {
    INITIAL_PLAN,
    REQUIREMENTS_CHANGED,
    POLICY_CHANGED,
    RECOVERY_REPLAN,
    REVIEW_FEEDBACK,
    MANUAL_REVISION
}
