package io.crewscope.domain.action;

/** Stable reason codes for cancelling an action before external execution begins. */
public enum ActionCancellationReason {
    CONFIRMATION_CANCELLED,
    MEMBER_CANCELLED,
    DEPENDENCY_FAILED,
    BUNDLE_EXPIRED,
    AUTHORITY_INVALIDATED
}
