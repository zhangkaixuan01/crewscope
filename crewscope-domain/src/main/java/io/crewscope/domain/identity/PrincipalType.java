package io.crewscope.domain.identity;

/** Stable behavior-subject types persisted by the Principal aggregate. */
public enum PrincipalType {
    USER,
    PERSONAL_AGENT,
    TEAM_AGENT,
    SPECIALIST_AGENT,
    SERVICE;

    public boolean isAgent() {
        return this == PERSONAL_AGENT || this == TEAM_AGENT || this == SPECIALIST_AGENT;
    }
}
