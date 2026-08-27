package io.crewscope.application.collaboration;

import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Browser-safe Lark Connection view; secret and internal authorization identifiers are absent. */
public record LarkConnectionView(
        ConnectionId connectionId,
        TeamId teamId,
        Optional<ProviderBindingId> providerBindingId,
        Optional<Long> providerBindingVersion,
        String maskedAppId,
        ConnectionStatus status,
        CredentialStatus credentialStatus,
        Optional<UtcTimestamp> expiresAt,
        UtcTimestamp createdAt,
        UtcTimestamp updatedAt,
        long version) {

    public LarkConnectionView {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        providerBindingVersion = Objects.requireNonNull(
                providerBindingVersion, "providerBindingVersion");
        maskedAppId = Objects.requireNonNull(maskedAppId, "maskedAppId");
        status = Objects.requireNonNull(status, "status");
        credentialStatus = Objects.requireNonNull(credentialStatus, "credentialStatus");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || providerBindingVersion.filter(value -> value < 0).isPresent()
                || providerBindingId.isPresent() != providerBindingVersion.isPresent()) {
            throw new IllegalArgumentException(
                    "versions must be non-negative and ProviderBinding coordinates complete");
        }
    }
}
