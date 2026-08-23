package io.crewscope.application.model;

import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionRevocationReason;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.UUID;

/** Strongly versioned irreversible revocation request for a connection and its credential. */
public record RevokeModelConnectionCredentialCommand(
        OrganizationId organizationId,
        ModelConnectionId connectionId,
        long expectedConnectionVersion,
        ModelCredentialVersion expectedCredentialVersion,
        CredentialRevocationReason credentialReason,
        ModelConnectionRevocationReason connectionReason,
        PrincipalId actor,
        UUID correlationId) {

    public RevokeModelConnectionCredentialCommand {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (expectedConnectionVersion < 0) {
            throw new IllegalArgumentException("expectedConnectionVersion must not be negative");
        }
        Objects.requireNonNull(expectedCredentialVersion, "expectedCredentialVersion");
        Objects.requireNonNull(credentialReason, "credentialReason");
        Objects.requireNonNull(connectionReason, "connectionReason");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
