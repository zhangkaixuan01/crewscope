package io.crewscope.application.github;

import java.util.Objects;

/** Verifies remote identity and freezes the exact effective repository policy. */
public record VerifyGitHubConnectionRequest(
        GitHubAccessRequest access,
        GitHubAuthenticationType authenticationType,
        GitHubRepositoryPolicy repositoryPolicy) {

    public VerifyGitHubConnectionRequest {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(authenticationType, "authenticationType");
        Objects.requireNonNull(repositoryPolicy, "repositoryPolicy");
    }
}
