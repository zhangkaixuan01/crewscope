package io.crewscope.domain.team;

/** Durable Team membership lifecycle. */
public enum TeamMemberStatus {
    INVITED,
    ACTIVE,
    SUSPENDED,
    LEFT,
    REMOVED
}
