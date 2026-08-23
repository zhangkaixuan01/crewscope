package io.crewscope.application.model;

import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted create input; the API key is deliberately supplied through a separate closeable value. */
public record CreateModelConnectionCredentialCommand(
        ModelConnectionId connectionId,
        ModelProviderKey providerKey,
        ModelConnectionOwner owner,
        ModelEndpoint endpoint,
        ModelRegion region,
        ModelBillingSubject billingSubject,
        CredentialId credentialId,
        CredentialSubject credentialSubject,
        String credentialKey,
        Map<String, String> credentialMetadata,
        Optional<UtcTimestamp> credentialExpiresAt,
        PrincipalId actor,
        UUID correlationId) {

    public CreateModelConnectionCredentialCommand {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(providerKey, "providerKey");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(billingSubject, "billingSubject");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(credentialSubject, "credentialSubject");
        if (credentialKey == null || credentialKey.isBlank()) {
            throw new IllegalArgumentException("credentialKey must not be blank");
        }
        credentialKey = credentialKey.strip();
        credentialMetadata = Map.copyOf(Objects.requireNonNull(credentialMetadata, "credentialMetadata"));
        credentialExpiresAt = Objects.requireNonNull(credentialExpiresAt, "credentialExpiresAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
        if (!owner.organizationId().equals(credentialSubject.organizationId())) {
            throw new IllegalArgumentException("Credential subject must belong to the connection organization");
        }
    }
}
