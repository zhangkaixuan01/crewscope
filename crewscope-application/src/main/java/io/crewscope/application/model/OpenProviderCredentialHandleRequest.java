package io.crewscope.application.model;

import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.UUID;

/** Trusted request to issue a purpose-bound, short-lived provider credential capability. */
public record OpenProviderCredentialHandleRequest(
        OrganizationId organizationId,
        ModelConnectionId connectionId,
        long expectedConnectionVersion,
        ModelCredentialVersion expectedCredentialVersion,
        PrincipalId actor,
        String purpose,
        UUID correlationId) {

    public OpenProviderCredentialHandleRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (expectedConnectionVersion < 0) {
            throw new IllegalArgumentException("expectedConnectionVersion must not be negative");
        }
        Objects.requireNonNull(expectedCredentialVersion, "expectedCredentialVersion");
        Objects.requireNonNull(actor, "actor");
        if (purpose == null || purpose.isBlank() || purpose.strip().length() > 200) {
            throw new IllegalArgumentException("purpose must contain between 1 and 200 characters");
        }
        purpose = purpose.strip();
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
