package io.crewscope.domain.action;

/** Explicit MVP disposition after cancellation; CrewScope never invents reverse writes. */
public enum CompensationDisposition {
    NOT_REQUIRED,
    MANUAL_REVIEW_REQUIRED
}
