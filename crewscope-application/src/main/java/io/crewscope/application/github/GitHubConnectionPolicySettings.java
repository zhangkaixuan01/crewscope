package io.crewscope.application.github;

import java.util.Objects;
import java.util.Set;

/** Deployment-owned GitHub policy dimensions intersected with each persisted Connection Grant. */
public record GitHubConnectionPolicySettings(
        Set<String> allowedOwnerLogins,
        boolean allowPrivateRepositories,
        boolean allowInternalRepositories,
        boolean allowBroadUserOauth,
        boolean webhookReceiverConfigured) {

    public GitHubConnectionPolicySettings {
        allowedOwnerLogins = Set.copyOf(
                Objects.requireNonNull(allowedOwnerLogins, "allowedOwnerLogins"));
    }

    public GitHubRepositoryPolicy policyFor(Set<String> repositoryAllowlist) {
        return new GitHubRepositoryPolicy(
                repositoryAllowlist,
                allowedOwnerLogins,
                allowPrivateRepositories,
                allowInternalRepositories,
                allowBroadUserOauth);
    }
}
