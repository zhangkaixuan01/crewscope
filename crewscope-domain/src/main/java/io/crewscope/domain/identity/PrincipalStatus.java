package io.crewscope.domain.identity;

/** Principal access lifecycle; only ACTIVE Principals may act. */
public enum PrincipalStatus {
    ACTIVE,
    SUSPENDED,
    DISABLED,
    ARCHIVED
}
