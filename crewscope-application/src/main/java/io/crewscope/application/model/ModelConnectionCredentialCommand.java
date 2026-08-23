package io.crewscope.application.model;

import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.UUID;

/** Shared strong preconditions for verify and rotate operations. */
public record ModelConnectionCredentialCommand(
        OrganizationId organizationId,
        ModelConnectionId connectionId,
        long expectedConnectionVersion,
        ModelCredentialVersion expectedCredentialVersion,
        PrincipalId actor,
        UUID correlationId) {

    public ModelConnectionCredentialCommand {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (expectedConnectionVersion < 0) {
            throw new IllegalArgumentException("expectedConnectionVersion must not be negative");
        }
        Objects.requireNonNull(expectedCredentialVersion, "expectedCredentialVersion");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
