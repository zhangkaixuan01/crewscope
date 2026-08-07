package io.crewscope.domain.shared.event;

/** Principal kinds allowed to cause a domain event. */
public enum EventActorType {
    USER,
    PERSONAL_AGENT,
    TEAM_AGENT,
    SPECIALIST_AGENT,
    SERVICE
}
