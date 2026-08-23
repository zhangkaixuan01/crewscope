package io.crewscope.application.model;

import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelProviderKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Server-owned facts used to compute a Principal's real selectable model intersection. */
public record SelectableModelCatalogQuery(
        OrganizationId organizationId,
        AgentOwnership agentOwnership,
        Optional<PrincipalId> ownerUserPrincipalId,
        AgentExecutionScope executionScope,
        AgentTemplateDefinition template,
        SafeModelGenerateOptions generateOptions,
        AgentModelPolicyConstraints policyConstraints,
        Set<ModelProviderKey> allowedProviderKeys,
        Set<ModelCatalogCoordinate> allowedCatalogCoordinates,
        AgentExecutionAuthorizationFacts authorization,
        UtcTimestamp effectiveAt) {

    public SelectableModelCatalogQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        agentOwnership = Objects.requireNonNull(agentOwnership, "agentOwnership");
        ownerUserPrincipalId = Objects.requireNonNull(
                ownerUserPrincipalId, "ownerUserPrincipalId");
        executionScope = Objects.requireNonNull(executionScope, "executionScope");
        template = Objects.requireNonNull(template, "template");
        generateOptions = Objects.requireNonNull(generateOptions, "generateOptions");
        policyConstraints = Objects.requireNonNull(policyConstraints, "policyConstraints");
        allowedProviderKeys = Set.copyOf(
                Objects.requireNonNull(allowedProviderKeys, "allowedProviderKeys"));
        allowedCatalogCoordinates = Set.copyOf(
                Objects.requireNonNull(
                        allowedCatalogCoordinates, "allowedCatalogCoordinates"));
        authorization = Objects.requireNonNull(authorization, "authorization");
        effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (!organizationId.equals(agentOwnership.organizationId())) {
            throw new IllegalArgumentException("Agent ownership must belong to the query Organization");
        }
    }

    public boolean allowsProvider(ModelProviderKey providerKey) {
        return allowedProviderKeys.isEmpty() || allowedProviderKeys.contains(providerKey);
    }

    public boolean allowsCatalog(ModelCatalogCoordinate coordinate) {
        return allowedCatalogCoordinates.isEmpty()
                || allowedCatalogCoordinates.contains(coordinate);
    }
}
