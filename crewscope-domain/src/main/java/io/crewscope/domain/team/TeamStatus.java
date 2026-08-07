package io.crewscope.domain.team;

/** Team lifecycle; archived Teams retain history and no longer accept members or work. */
public enum TeamStatus {
    ACTIVE,
    ARCHIVED
}
