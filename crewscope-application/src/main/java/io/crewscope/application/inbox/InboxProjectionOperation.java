package io.crewscope.application.inbox;

/** Reviewed projection actions supported by the member Inbox event registry. */
public enum InboxProjectionOperation {
    RESPONSIBILITY_ASSIGNED,
    RESPONSIBILITY_RELEASED,
    REVIEW_OPENED,
    REVIEW_COMPLETED,
    REVIEW_SUPERSEDED,
    CONFIRMATION_OPENED,
    CONFIRMATION_COMPLETED,
    CONFIRMATION_CANCELLED,
    TASK_EXCEPTION_OPENED,
    TASK_EXCEPTION_RESOLVED,
    ACTION_DELIVERY_REFRESHED
}
