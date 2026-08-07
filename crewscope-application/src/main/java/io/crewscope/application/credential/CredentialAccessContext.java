package io.crewscope.application.credential;

import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Set;

/** Trusted action context containing the exact credentials authorized for one purpose. */
public record CredentialAccessContext(
        OrganizationId organizationId,
        PrincipalId principalId,
        Set<CredentialId> allowedCredentialIds,
        String purpose) {

    public CredentialAccessContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principalId, "principalId");
        allowedCredentialIds = Set.copyOf(
                Objects.requireNonNull(allowedCredentialIds, "allowedCredentialIds"));
        purpose = CredentialCreateRequest.requireText(purpose, "purpose", 200);
    }

    public boolean allows(CredentialReference reference) {
        CredentialReference required = Objects.requireNonNull(reference, "reference");
        return organizationId.equals(required.organizationId())
                && allowedCredentialIds.contains(required.credentialId());
    }
}
