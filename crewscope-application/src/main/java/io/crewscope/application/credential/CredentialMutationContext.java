package io.crewscope.application.credential;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Trusted organization and actor used for credential lifecycle mutations. */
public record CredentialMutationContext(
        OrganizationId organizationId, PrincipalId principalId) {

    public CredentialMutationContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principalId, "principalId");
    }
}
