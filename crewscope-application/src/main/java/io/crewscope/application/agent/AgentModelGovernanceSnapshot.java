package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.policy.PolicyPackReference;
import java.util.Objects;
import java.util.Set;

/** Immutable policy decision applied consistently to catalog, write and Preflight operations. */
public record AgentModelGovernanceSnapshot(
        PolicyPackReference policyPack,
        AgentModelPolicyConstraints policyConstraints,
        Set<ModelProviderKey> allowedProviderKeys,
        Set<ModelCatalogCoordinate> allowedCatalogCoordinates) {

    public AgentModelGovernanceSnapshot {
        policyPack = Objects.requireNonNull(policyPack, "policyPack");
        policyConstraints = Objects.requireNonNull(policyConstraints, "policyConstraints");
        allowedProviderKeys = Set.copyOf(
                Objects.requireNonNull(allowedProviderKeys, "allowedProviderKeys"));
        allowedCatalogCoordinates = Set.copyOf(
                Objects.requireNonNull(
                        allowedCatalogCoordinates, "allowedCatalogCoordinates"));
    }

    public boolean allowsProvider(ModelProviderKey providerKey) {
        return allowedProviderKeys.isEmpty() || allowedProviderKeys.contains(providerKey);
    }

    public boolean allowsCatalog(ModelCatalogCoordinate coordinate) {
        return allowedCatalogCoordinates.isEmpty()
                || allowedCatalogCoordinates.contains(coordinate);
    }
}
