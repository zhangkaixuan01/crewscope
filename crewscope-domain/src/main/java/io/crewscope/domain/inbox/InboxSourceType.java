package io.crewscope.domain.inbox;

/** Canonical domain fact families allowed to produce a member Inbox source. */
public enum InboxSourceType {
    RESPONSIBILITY_ASSIGNMENT,
    REVIEW_REQUEST,
    ACTION_CONFIRMATION,
    TASK_EXECUTION,
    ACTION_DELIVERY,
    NOTIFICATION_DELIVERY
}
