package io.crewscope.application.model;

import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Computes the server-side catalog intersection; rejected candidates are omitted without detail. */
public final class SelectableModelCatalogService {

    private final ModelProviderDefinitionRepository providers;
    private final ModelConnectionRepository connections;
    private final ModelCatalogEntryRepository catalogs;
    private final ModelPriceScheduleRepository prices;
    private final ModelConnectionAvailabilityVerifier availability;
    private final int maximumCatalogEntriesPerProvider;

    public SelectableModelCatalogService(
            ModelProviderDefinitionRepository providers,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionAvailabilityVerifier availability,
            int maximumCatalogEntriesPerProvider) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.availability = Objects.requireNonNull(availability, "availability");
        if (maximumCatalogEntriesPerProvider < 1
                || maximumCatalogEntriesPerProvider > 10_000) {
            throw new IllegalArgumentException(
                    "maximumCatalogEntriesPerProvider must be between 1 and 10000");
        }
        this.maximumCatalogEntriesPerProvider = maximumCatalogEntriesPerProvider;
    }

    public List<SelectableModelOption> findSelectable(SelectableModelCatalogQuery query) {
        SelectableModelCatalogQuery required = Objects.requireNonNull(query, "query");
        try {
            required.template().requireExecutable(
                    required.agentOwnership(), required.executionScope());
        } catch (DomainValidationException rejected) {
            return List.of();
        }
        AgentModelPolicyConstraints policy = required.policyConstraints()
                .withTemplateRequirements(required.template(), required.generateOptions());
        List<SelectableModelOption> result = new ArrayList<>();
        required.authorization().usableConnectionIds().stream()
                .sorted(Comparator.comparing(Object::toString))
                .map(id -> connections.findById(required.organizationId(), id).orElse(null))
                .filter(Objects::nonNull)
                .forEach(connection -> appendSelectable(result, required, policy, connection));
        return result.stream()
                .sorted(Comparator.comparing((SelectableModelOption value) ->
                                value.selection().providerKey().toString())
                        .thenComparing(value -> value.selection()
                                .catalogCoordinate().modelId().toString())
                        .thenComparing(value -> value.connectionOwner().type().name())
                        .thenComparing(value -> value.selection().connectionId().toString()))
                .toList();
    }

    private void appendSelectable(
            List<SelectableModelOption> target,
            SelectableModelCatalogQuery query,
            AgentModelPolicyConstraints policy,
            ModelConnection connection) {
        ModelProviderDefinition provider = providers.findByKey(connection.providerKey()).orElse(null);
        if (provider == null || !query.allowsProvider(provider.providerKey())) {
            return;
        }
        try {
            availability.requireAvailable(
                    connection,
                    query.authorization().requestingPrincipalId(),
                    query.effectiveAt());
        } catch (AgentModelPreflightException rejected) {
            return;
        }
        for (ModelCatalogEntry catalog : catalogs.findPage(
                provider.providerKey(), 0, maximumCatalogEntriesPerProvider)) {
            if (!query.allowsCatalog(catalog.coordinate())) {
                continue;
            }
            ModelPriceRevision price = prices.findEffectivePrice(
                            catalog.coordinate(), query.effectiveAt())
                    .orElse(null);
            if (price == null) {
                continue;
            }
            try {
                AgentModelSelection selection = AgentModelSelection.capture(connection, catalog);
                ResolvedModelSelection.resolve(
                        ResolvedModelRole.PRIMARY,
                        selection,
                        provider,
                        connection,
                        catalog,
                        price,
                        query.agentOwnership(),
                        query.ownerUserPrincipalId(),
                        query.executionScope(),
                        policy,
                        query.authorization());
                target.add(new SelectableModelOption(
                        selection,
                        provider.displayName(),
                        catalog.displayName(),
                        connection.owner(),
                        connection.region(),
                        catalog.contextWindowTokens(),
                        catalog.maximumOutputTokens(),
                        catalog.capabilities(),
                        price.tokenPrice()));
            } catch (AgentModelPreflightException | DomainValidationException rejected) {
                // The catalog is a safe allow-list projection and does not expose rejection detail.
            }
        }
    }
}
