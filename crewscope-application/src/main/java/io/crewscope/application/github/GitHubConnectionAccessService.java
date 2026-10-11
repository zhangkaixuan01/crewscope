package io.crewscope.application.github;

import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Revalidates the full authorization fact set for one remote call: current
 * Connection, Grant, binding access and repository policy. Extracted from
 * {@link GitHubConnectionApplicationService} in M11-E01; the check ordering
 * (visibility, manage ownership, version suspension, grant, policy) is
 * unchanged.
 */
public final class GitHubConnectionAccessService {

    private final ConnectionGrantRepository grants;
    private final GitHubProviderRepository githubRepository;
    private final ProviderDefinitionRepository definitions;
    private final ProviderImplementationRepository implementations;
    private final TimeProvider timeProvider;
    private final GitHubConnectionPolicySettings policySettings;
    private final GitHubConnectionAuthorizationService authorization;

    public GitHubConnectionAccessService(
            ConnectionGrantRepository grants,
            GitHubProviderRepository githubRepository,
            ProviderDefinitionRepository definitions,
            ProviderImplementationRepository implementations,
            TimeProvider timeProvider,
            GitHubConnectionPolicySettings policySettings,
            GitHubConnectionAuthorizationService authorization) {
        this.grants = Objects.requireNonNull(grants, "grants");
        this.githubRepository = Objects.requireNonNull(githubRepository, "githubRepository");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.implementations = Objects.requireNonNull(implementations, "implementations");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.policySettings = Objects.requireNonNull(policySettings, "policySettings");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    Optional<ConnectionGrant> currentGrant(Connection connection) {
        List<ConnectionGrant> candidates = grants
                .findByConnectionAndGrantee(connection.id(), connection.owner()).stream()
                .filter(value -> value.connectionOwner().equals(connection.owner()))
                .filter(value -> value.status() == ConnectionGrantStatus.ACTIVE)
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    ConnectionGrant requireGrant(Connection connection) {
        return currentGrant(connection).orElseThrow(() -> new GitHubProviderException(
                GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                "GitHub Connection Grant is unavailable"));
    }

    GitHubConnectionProfile requireCurrentProfile(Connection connection) {
        return githubRepository
                .findProfile(connection.organizationId(), connection.id(), connection.version())
                .filter(value -> value.isCurrentFor(connection.version()))
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Connection has not been verified"));
    }

    GitHubConnectionAccessFacts requireManagedAccess(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            UUID correlationId) {
        return requireManagedAccess(
                context,
                organizationId,
                connectionId,
                expectedVersion,
                correlationId,
                null);
    }

    GitHubConnectionAccessFacts requireManagedAccess(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            UUID correlationId,
            ProviderAccessScope bindingAccess) {
        Connection connection = authorization.requireVisibleConnection(
                context, organizationId, connectionId);
        authorization.requireManageOwner(context, connection.owner(), timeProvider.now());
        if (connection.version() != expectedVersion) {
            // Let the aggregate produce the canonical optimistic-lock domain error shape.
            connection.suspend(expectedVersion, context.actor(), timeProvider.now());
        }
        ConnectionGrant grant = requireGrant(connection);
        ProviderAccessScope requestedAccess = bindingAccess == null
                ? grant.grantedAccess()
                : grant.effectiveAccess(bindingAccess, connection, timeProvider.now())
                        .orElseThrow(() -> new GitHubProviderException(
                                GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                                "GitHub Provider Binding authorization is unavailable"));
        GitHubRepositoryPolicy policy = policyFor(grant);
        GitHubAuthenticationType authentication =
                GitHubConnectionAuthorizationService.authenticationType(connection.owner().type());
        return new GitHubConnectionAccessFacts(
                connection,
                grant,
                authentication,
                policy,
                new GitHubAccessRequest(
                        organizationId,
                        connection.id(),
                        connection.version(),
                        grant.id(),
                        grant.version(),
                        grant.grantee(),
                        requestedAccess,
                        context.actor().id(),
                        Objects.requireNonNull(correlationId, "correlationId")));
    }

    ProviderAccessScope requireCurrentBindingAccess(
            ProviderBinding binding, Connection connection, UtcTimestamp now) {
        ConnectionGrant grant = requireGrant(connection);
        if (!binding.owner().equals(connection.owner())
                || binding.providerType() != ProviderType.SOURCE_CODE
                || binding.connectionGrantId().filter(grant.id()::equals).isEmpty()
                || binding.connectionGrantVersion().filter(version -> version == grant.version()).isEmpty()
                || binding.executionIdentity()
                        .filter(GitHubConnectionAuthorizationService
                                .executionIdentity(connection.owner().type())::equals)
                        .isEmpty()) {
            throw new GitHubProviderException(
                    GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                    "GitHub Provider Binding authorization is unavailable");
        }
        ProviderDefinition definition = definitions
                .findById(connection.organizationId(), binding.definitionId())
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Provider definition is unavailable"));
        ProviderImplementation implementation = implementations
                .findById(connection.organizationId(), binding.implementationId())
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Provider implementation is unavailable"));
        return binding.currentAccess(
                        definition,
                        implementation,
                        Optional.of(connection),
                        Optional.of(grant),
                        now)
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                        "GitHub Provider Binding is stale"));
    }

    ProviderAccessScope selectedRepositoryAccess(
            OrganizationId organizationId,
            ConnectionId connectionId,
            Connection connection,
            ConnectionGrant grant,
            Set<String> externalRepositoryIds,
            UtcTimestamp now) {
        Set<String> ids = Set.copyOf(Objects.requireNonNull(externalRepositoryIds, "externalRepositoryIds"));
        if (ids.isEmpty()) {
            throw new DomainValidationException(
                    "githubProviderBinding.repositoryIds", "must not be empty");
        }
        Set<String> resources = ids.stream()
                .map(id -> githubRepository.findRepository(organizationId, connectionId, id)
                        .filter(value -> value.connectionVersion() == connection.version())
                        .filter(value -> value.status() == GitHubRepositoryStatus.DELIVERABLE)
                        .filter(value -> value.isCurrentAt(now))
                        .map(GitHubRepositoryCatalogEntry::grantResourceKey)
                        .orElseThrow(() -> new GitHubProviderException(
                                GitHubProviderErrorCode.RESOURCE_UNAVAILABLE,
                                "GitHub repository is unavailable")))
                .collect(Collectors.toSet());
        ProviderAccessScope requested = new ProviderAccessScope(
                GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                io.crewscope.domain.provider.ProviderResourceScope.of(resources.toArray(String[]::new)));
        return grant.effectiveAccess(requested, connection, now)
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                        "GitHub Connection Grant does not authorize the selected repository"));
    }

    private GitHubRepositoryPolicy policyFor(ConnectionGrant grant) {
        return policySettings.policyFor(
                GitHubRepositoryResources.repositoryAllowlist(grant.grantedAccess().resources()));
    }
}
