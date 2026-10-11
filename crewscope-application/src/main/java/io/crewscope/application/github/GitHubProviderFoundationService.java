package io.crewscope.application.github;

import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bootstraps or revalidates the built-in GitHub Provider definition and
 * implementation for one Organization. Extracted from
 * {@link GitHubConnectionApplicationService} in M11-E01; the constructor-time
 * registration compatibility check and the conflict rules are unchanged.
 */
public final class GitHubProviderFoundationService {

    private final ProviderBootstrapLock providerBootstrapLock;
    private final ProviderDefinitionRepository definitions;
    private final ProviderImplementationRepository implementations;
    private final BuiltInProviderRegistration githubRegistration;

    public GitHubProviderFoundationService(
            ProviderBootstrapLock providerBootstrapLock,
            ProviderDefinitionRepository definitions,
            ProviderImplementationRepository implementations,
            BuiltInProviderRegistration githubRegistration) {
        this.providerBootstrapLock = Objects.requireNonNull(
                providerBootstrapLock, "providerBootstrapLock");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.implementations = Objects.requireNonNull(implementations, "implementations");
        this.githubRegistration = Objects.requireNonNull(githubRegistration, "githubRegistration");
        if (githubRegistration.type() != ProviderType.SOURCE_CODE
                || !GitHubConnectionApplicationService.CONNECTOR_KEY
                        .equals(githubRegistration.implementationKey())
                || !githubRegistration.capabilities()
                        .equals(GitHubConnectionApplicationService.DELIVERY_CAPABILITIES)) {
            throw new IllegalArgumentException("githubRegistration is incompatible");
        }
    }

    GitHubProviderFoundationFacts providerFoundation(
            OrganizationId organizationId, Principal actor, UtcTimestamp now) {
        providerBootstrapLock.acquire(organizationId);
        ProviderDefinition candidateDefinition = ProviderDefinition.create(
                githubRegistration.definitionId(organizationId),
                organizationId,
                githubRegistration.definitionKey(),
                githubRegistration.type(),
                githubRegistration.interfaceVersion(),
                githubRegistration.displayName(),
                githubRegistration.capabilities(),
                actor,
                now);
        ProviderDefinition definition = definitions
                .findByKey(organizationId, githubRegistration.definitionKey())
                .orElseGet(() -> definitions.create(candidateDefinition));
        if (!definition.id().equals(candidateDefinition.id())
                || !definition.capabilities().equals(GitHubConnectionApplicationService.DELIVERY_CAPABILITIES)
                || definition.type() != ProviderType.SOURCE_CODE
                || !definition.isActive()) {
            throw new DomainValidationException(
                    "githubProvider.definition", "conflicts with the built-in contract");
        }
        List<ProviderImplementation> sameKey = implementations
                .findByDefinition(organizationId, definition.id()).stream()
                .filter(value -> value.key().equals(githubRegistration.implementationKey()))
                .toList();
        if (sameKey.size() > 1) {
            throw new DomainValidationException(
                    "githubProvider.implementation", "contains ambiguous registrations");
        }
        ProviderImplementation implementation = sameKey.isEmpty()
                ? implementations.create(ProviderImplementation.create(
                        githubRegistration.implementationId(organizationId),
                        definition,
                        githubRegistration.implementationKey(),
                        githubRegistration.implementationVersion(),
                        githubRegistration.capabilities(),
                        ProviderConnectionRequirement.REQUIRED,
                        Optional.of(GitHubConnectionApplicationService.CONNECTOR_KEY),
                        actor,
                        now))
                : sameKey.get(0);
        if (!implementation.supports(
                        definition, GitHubConnectionApplicationService.DELIVERY_CAPABILITIES)
                || implementation.connectionRequirement() != ProviderConnectionRequirement.REQUIRED
                || implementation.connectorKey()
                        .filter(GitHubConnectionApplicationService.CONNECTOR_KEY::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "githubProvider.implementation", "conflicts with the built-in contract");
        }
        return new GitHubProviderFoundationFacts(definition, implementation);
    }
}
