package io.crewscope.domain.provider;

/** External identity form selected by the owning Connection. */
public enum ProviderExecutionIdentity {
    DELEGATED_USER,
    TEAM_SERVICE_ACCOUNT,
    ORGANIZATION_SERVICE_ACCOUNT
}
