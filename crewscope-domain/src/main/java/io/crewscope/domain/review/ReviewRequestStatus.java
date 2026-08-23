package io.crewscope.domain.review;

/** Lifecycle of one exact ReviewRequest version. */
public enum ReviewRequestStatus {
    OPEN,
    IN_PROGRESS,
    COMPLETED,
    INVALIDATED
}
