package io.crewscope.domain.action;

/** Decision returned by the single ExternalResult merge function. */
public enum ExternalMergeOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    CONFLICT,
    MANUAL_TERMINAL_CONFLICT
}
