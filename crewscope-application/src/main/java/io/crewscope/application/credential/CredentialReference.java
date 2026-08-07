package io.crewscope.application.credential;

import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Tenant-qualified credential reference used by every lookup and mutation. */
public record CredentialReference(OrganizationId organizationId, CredentialId credentialId) {

    public CredentialReference {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(credentialId, "credentialId");
    }
}
