package io.crewscope.domain.organization.governance;

/** Lifecycle of one organization data export; terminal states are COMPLETED and CANCELLED. */
public enum DataExportJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED
}
