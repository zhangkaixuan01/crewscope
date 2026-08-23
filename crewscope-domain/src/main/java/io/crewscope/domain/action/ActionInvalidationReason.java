package io.crewscope.domain.action;

/** Safe reason explaining why an unexecuted Bundle can no longer be confirmed or dispatched. */
public enum ActionInvalidationReason {
    EXPIRED,
    REVIEW_CHANGED,
    RESPONSIBILITY_CHANGED,
    PROVIDER_AUTHORIZATION_CHANGED,
    POLICY_CHANGED,
    SAFETY_OVERLAY_CHANGED,
    TARGET_PRECONDITION_CHANGED,
    AUTHORITY_UNAVAILABLE
}
