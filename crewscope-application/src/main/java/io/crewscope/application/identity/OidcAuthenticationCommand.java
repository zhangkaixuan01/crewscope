package io.crewscope.application.identity;

import io.crewscope.domain.identity.ExternalAccountIdentity;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-assembled first-fact of one completed OIDC code exchange; carries no IdP tokens. */
public record OidcAuthenticationCommand(
        ExternalAccountIdentity identity,
        RegistrationMode registrationMode,
        OrganizationId organizationId,
        UUID correlationId,
        Optional<UUID> causationId) {

    public OidcAuthenticationCommand {
        identity = Objects.requireNonNull(identity, "identity");
        registrationMode = Objects.requireNonNull(registrationMode, "registrationMode");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId");
    }
}
