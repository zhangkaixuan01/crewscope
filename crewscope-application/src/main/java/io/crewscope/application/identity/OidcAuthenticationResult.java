package io.crewscope.application.identity;

import java.util.Objects;

/** Outcome of one federated authentication: the resolved coordinates and whether it provisioned. */
public record OidcAuthenticationResult(AccountOrganizationResolution resolution, boolean firstLogin) {

    public OidcAuthenticationResult {
        resolution = Objects.requireNonNull(resolution, "resolution");
    }
}
