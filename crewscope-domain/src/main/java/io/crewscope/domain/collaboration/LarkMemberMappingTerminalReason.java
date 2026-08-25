package io.crewscope.domain.collaboration;

/** Stable non-sensitive terminal reason for a confirmed member mapping. */
public enum LarkMemberMappingTerminalReason {
    ADMIN_REVOKED,
    MEMBER_LEFT,
    AUTHORIZATION_DRIFT,
    IDENTITY_REPLACED;

    public boolean supports(LarkMemberMappingStatus status) {
        return switch (status) {
            case ACTIVE -> false;
            case REVOKED -> this == ADMIN_REVOKED || this == MEMBER_LEFT;
            case INVALIDATED -> this == AUTHORIZATION_DRIFT || this == IDENTITY_REPLACED;
        };
    }
}
