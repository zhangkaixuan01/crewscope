package io.crewscope.application.github;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Non-secret GitHub identity and permission facts bound to one exact Connection version. */
public record GitHubConnectionProfile(
        UUID id,
        OrganizationId organizationId,
        ConnectionId connectionId,
        long connectionVersion,
        ProviderOwner connectionOwner,
        ProviderExecutionIdentity externalIdentity,
        GitHubAuthenticationType authenticationType,
        String externalAccountId,
        String externalAccountLogin,
        Set<GitHubPermission> grantedPermissions,
        String repositoryAllowlistHash,
        GitHubConnectionProfileStatus status,
        long version,
        AuditMetadata audit) {

    public GitHubConnectionProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        if (connectionVersion < 0 || version < 0) {
            throw new IllegalArgumentException("GitHub connection versions must not be negative");
        }
        connectionOwner = Objects.requireNonNull(connectionOwner, "connectionOwner");
        if (!organizationId.equals(connectionOwner.organizationId())) {
            throw new IllegalArgumentException("GitHub connection owner must belong to the Organization");
        }
        Objects.requireNonNull(externalIdentity, "externalIdentity");
        Objects.requireNonNull(authenticationType, "authenticationType");
        validateIdentity(connectionOwner.type(), externalIdentity, authenticationType);
        externalAccountId = bounded(externalAccountId, "externalAccountId", 100);
        externalAccountLogin = bounded(externalAccountLogin, "externalAccountLogin", 255);
        grantedPermissions = Set.copyOf(Objects.requireNonNull(grantedPermissions, "grantedPermissions"));
        if (!grantedPermissions.containsAll(GitHubPermission.minimumDraftDelivery())) {
            throw new IllegalArgumentException("GitHub delivery permission set is incomplete");
        }
        if (grantedPermissions.stream()
                .anyMatch(GitHubPermission.forbiddenElevatedPermissions()::contains)) {
            throw new IllegalArgumentException("GitHub delivery permission set is too broad");
        }
        repositoryAllowlistHash = GitHubHash.requireHash(
                repositoryAllowlistHash, "repositoryAllowlistHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(audit, "audit");
    }

    public boolean isCurrentFor(long currentConnectionVersion) {
        return status == GitHubConnectionProfileStatus.ACTIVE
                && connectionVersion == currentConnectionVersion;
    }

    private static void validateIdentity(
            ProviderOwnerType owner,
            ProviderExecutionIdentity identity,
            GitHubAuthenticationType authentication) {
        boolean valid = (owner == ProviderOwnerType.TEAM
                        && identity == ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT
                        && authentication == GitHubAuthenticationType.APP_INSTALLATION)
                || (owner == ProviderOwnerType.USER
                        && identity == ProviderExecutionIdentity.DELEGATED_USER
                        && authentication == GitHubAuthenticationType.OAUTH_USER);
        if (!valid) {
            throw new IllegalArgumentException("GitHub owner, authentication and identity shape is invalid");
        }
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = GitHubHash.requireText(value);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds its maximum length");
        }
        return normalized;
    }
}
