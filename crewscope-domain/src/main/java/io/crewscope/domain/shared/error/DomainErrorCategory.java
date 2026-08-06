package io.crewscope.domain.shared.error;

/** Transport-neutral error category used by API and tool adapters for stable status mapping. */
public enum DomainErrorCategory {
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    POLICY
}
