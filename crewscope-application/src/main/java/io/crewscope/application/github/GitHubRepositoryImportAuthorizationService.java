package io.crewscope.application.github;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingQuery;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Revalidates every mutable authority fact needed to import one GitHub repository. */
public final class GitHubRepositoryImportAuthorizationService {

    private final GitHubProviderRepository githubRepositories;
    private final ConnectionRepository connections;
    private final ConnectionGrantRepository grants;
    private final ProviderBindingRepository providerBindings;
    private final RepositoryBindingAccessPolicy accessPolicy;
    private final GitHubConnectionPolicySettings policySettings;
    private final TimeProvider timeProvider;

    public GitHubRepositoryImportAuthorizationService(
            GitHubProviderRepository githubRepositories,
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            ProviderBindingRepository providerBindings,
            RepositoryBindingAccessPolicy accessPolicy,
            GitHubConnectionPolicySettings policySettings,
            TimeProvider timeProvider) {
        this.githubRepositories = Objects.requireNonNull(githubRepositories, "githubRepositories");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.providerBindings = Objects.requireNonNull(providerBindings, "providerBindings");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.policySettings = Objects.requireNonNull(policySettings, "policySettings");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public GitHubRepositoryImportAuthorization authorize(
            TeamAccessContext context,
            UUID correlationId,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            ConnectionId connectionId,
            long connectionVersion,
            ConnectionGrantId grantId,
            long grantVersion,
            String externalRepositoryId) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        UtcTimestamp now = timeProvider.now();
        var project = accessPolicy.requireAdministrator(
                trusted, organizationId, teamId, projectId, now);
        Connection connection = connections.findById(organizationId, connectionId)
                .filter(value -> value.version() == connectionVersion)
                .filter(value -> value.isUsableAt(now))
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Connection is unavailable"));
        if (connection.owner().teamId().filter(teamId::equals).isEmpty()) {
            throw failure(
                    GitHubProviderErrorCode.PERMISSION_DENIED,
                    "GitHub Connection is not owned by this Team");
        }
        ConnectionGrant grant = grants.findById(organizationId, grantId)
                .filter(value -> value.connectionId().equals(connection.id()))
                .filter(value -> value.version() == grantVersion)
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                        "GitHub Connection Grant is unavailable"));
        boolean bound = providerBindings.findCandidates(new ProviderBindingQuery(
                        organizationId,
                        teamId,
                        project.scope().workspaceId(),
                        Optional.of(projectId),
                        connection.owner(),
                        ProviderType.SOURCE_CODE,
                        Optional.of(connection.owner().type() == ProviderOwnerType.TEAM
                                ? ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT
                                : ProviderExecutionIdentity.DELEGATED_USER)))
                .stream()
                .filter(value -> value.connectionId().filter(connection.id()::equals).isPresent())
                .anyMatch(value -> value.status() == ProviderRegistrationStatus.ACTIVE);
        if (!bound) {
            throw failure(
                    GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                    "GitHub Connection is not bound to this Team");
        }
        GitHubRepositoryCatalogEntry catalog = githubRepositories.findRepository(
                        organizationId, connection.id(), externalRepositoryId)
                .filter(value -> value.connectionVersion() == connection.version())
                .filter(value -> value.status() == GitHubRepositoryStatus.DELIVERABLE)
                .filter(value -> value.isCurrentAt(now))
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.RESOURCE_UNAVAILABLE,
                        "GitHub repository is unavailable"));
        ProviderAccessScope requestedAccess = new ProviderAccessScope(
                GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                ProviderResourceScope.of(catalog.grantResourceKey()));
        boolean fullyGranted = grant.effectiveAccess(requestedAccess, connection, now)
                .filter(value -> value.capabilities().includes(
                        GitHubConnectionApplicationService.DELIVERY_CAPABILITIES))
                .filter(value -> value.resources().resources().contains(catalog.grantResourceKey()))
                .isPresent();
        if (!fullyGranted) {
            throw failure(
                    GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                    "GitHub Connection Grant is unavailable");
        }
        GitHubAccessRequest access = new GitHubAccessRequest(
                organizationId,
                connection.id(),
                connection.version(),
                grant.id(),
                grant.version(),
                grant.grantee(),
                requestedAccess,
                trusted.actor().id(),
                Objects.requireNonNull(correlationId, "correlationId"));
        return new GitHubRepositoryImportAuthorization(access, catalog, policyFor(grant));
    }

    private GitHubRepositoryPolicy policyFor(ConnectionGrant grant) {
        if (grant.grantedAccess().resources().unrestricted()
                || grant.grantedAccess().resources().resources().isEmpty()) {
            throw failure(
                    GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                    "GitHub Connection Grant is unavailable");
        }
        var repositories = grant.grantedAccess().resources().resources().stream()
                .filter(value -> value.startsWith("github:repository:"))
                .map(value -> value.substring("github:repository:".length()))
                .collect(Collectors.toSet());
        return policySettings.policyFor(repositories);
    }

    private static GitHubProviderException failure(
            GitHubProviderErrorCode code, String summary) {
        return new GitHubProviderException(code, summary);
    }
}
