package io.crewscope.application.github;

import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Sensitive-field-whitelisted GitHub Connection projection for application callers. */
public record GitHubConnectionView(
        String connectionId,
        ProviderOwnerType ownerType,
        Optional<TeamId> teamId,
        GitHubAuthenticationType authenticationType,
        Optional<ProviderExecutionIdentity> executionIdentity,
        Optional<String> externalAccountLogin,
        ConnectionStatus status,
        long version,
        List<String> repositoryAllowlist,
        Optional<CredentialStatus> credentialStatus,
        Optional<UtcTimestamp> expiresAt,
        Optional<UtcTimestamp> verifiedAt,
        UtcTimestamp createdAt,
        UtcTimestamp updatedAt) {

    public GitHubConnectionView {
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        ownerType = Objects.requireNonNull(ownerType, "ownerType");
        teamId = Objects.requireNonNull(teamId, "teamId");
        authenticationType = Objects.requireNonNull(authenticationType, "authenticationType");
        executionIdentity = Objects.requireNonNull(executionIdentity, "executionIdentity");
        externalAccountLogin = Objects.requireNonNull(externalAccountLogin, "externalAccountLogin");
        Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        repositoryAllowlist = List.copyOf(
                Objects.requireNonNull(repositoryAllowlist, "repositoryAllowlist"));
        credentialStatus = Objects.requireNonNull(credentialStatus, "credentialStatus");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
