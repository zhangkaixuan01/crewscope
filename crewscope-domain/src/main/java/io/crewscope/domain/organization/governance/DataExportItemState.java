package io.crewscope.domain.organization.governance;

/**
 * Per-item outcome of one export batch. DONE items never run again; SKIPPED records a data-plane
 * fact (e.g. VECTOR_STORE_DISABLED) that belongs in the frozen manifest for reconciliation.
 */
public enum DataExportItemState {
    PENDING,
    DONE,
    SKIPPED
}
