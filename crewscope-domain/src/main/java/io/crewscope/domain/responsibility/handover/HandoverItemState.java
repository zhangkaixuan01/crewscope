package io.crewscope.domain.responsibility.handover;

/**
 * Per-item outcome of one handover step. DONE items never run again; CONFLICT and DENIED record
 * why the item stopped and leave the underlying assignment untouched.
 */
public enum HandoverItemState {
    PENDING,
    DONE,
    CONFLICT,
    DENIED
}
