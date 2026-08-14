package io.crewscope.domain.task;

/** Real-time revocation categories that can only reduce a PolicySnapshot. */
public enum SafetyRestriction {
    PRINCIPAL_DISABLED,
    MEMBERSHIP_DISABLED,
    PROVIDER_BINDING_DISABLED,
    CONNECTION_REVOKED,
    CREDENTIAL_REVOKED,
    TOOL_DISABLED,
    CAPABILITY_DISABLED,
    MODEL_DISABLED,
    RESOURCE_BLOCKED,
    PLUGIN_KILL_SWITCH
}
