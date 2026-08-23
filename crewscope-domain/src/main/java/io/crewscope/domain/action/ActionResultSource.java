package io.crewscope.domain.action;

/** Trusted source that proved one external action result. */
public enum ActionResultSource {
    WRITE_RESPONSE,
    ACTIVE_QUERY,
    WEBHOOK,
    MANUAL,
    CONTROL
}
