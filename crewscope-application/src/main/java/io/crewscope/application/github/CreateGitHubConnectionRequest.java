package io.crewscope.application.github;

import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One-way input for a TEAM App Installation or USER OAuth GitHub connection. */
public record CreateGitHubConnectionRequest(
        GitHubAuthenticationType authenticationType,
        Optional<TeamId> teamId,
        CredentialSubjectType credentialSubjectType,
        String externalAccountId,
        Set<String> repositoryAllowlist,
        Optional<UtcTimestamp> expiresAt) {

    public CreateGitHubConnectionRequest {
        authenticationType = Objects.requireNonNull(authenticationType, "authenticationType");
        teamId = Objects.requireNonNull(teamId, "teamId");
        credentialSubjectType = Objects.requireNonNull(
                credentialSubjectType, "credentialSubjectType");
        if (externalAccountId == null || !externalAccountId.strip().matches("[0-9]{1,100}")) {
            throw new IllegalArgumentException("externalAccountId must be a GitHub numeric identity");
        }
        externalAccountId = externalAccountId.strip();
        repositoryAllowlist = Set.copyOf(
                Objects.requireNonNull(repositoryAllowlist, "repositoryAllowlist"));
        if (repositoryAllowlist.isEmpty()) {
            throw new IllegalArgumentException("repositoryAllowlist must not be empty");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
