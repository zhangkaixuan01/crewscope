package io.crewscope.application.correlation;

/** Canonical public sources; projected Audit duplicates of DomainEvents are suppressed. */
public enum CorrelationEventSource {
    DOMAIN_EVENT,
    AUDIT
}
