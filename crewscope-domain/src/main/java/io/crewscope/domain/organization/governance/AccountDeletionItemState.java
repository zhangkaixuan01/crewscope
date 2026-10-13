package io.crewscope.domain.organization.governance;

/**
 * Per-step outcome of the deletion coordination checklist. DONE steps never run again; CONFLICT
 * and DENIED record why a step stopped — restoring a backup re-runs process and the already
 * deleted steps settle as no-op DONE or CONFLICT, converging without rollback.
 */
public enum AccountDeletionItemState {
    PENDING,
    DONE,
    CONFLICT,
    DENIED
}
