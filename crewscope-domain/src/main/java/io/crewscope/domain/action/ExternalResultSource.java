package io.crewscope.domain.action;

/** Trusted channel that supplied one normalized Provider observation. */
public enum ExternalResultSource {
    WRITE_RESPONSE,
    WEBHOOK,
    ACTIVE_QUERY
}
