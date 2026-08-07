package io.crewscope.domain.team;

/** Trusted source that established a Team membership. */
public enum TeamJoinMethod {
    BOOTSTRAP,
    INVITATION,
    OIDC,
    SCIM,
    IMPORT
}
