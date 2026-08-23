package io.crewscope.application.agent;

import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionAvailabilityVerifier;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentExecutionScopePolicy;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentModelBindingSource;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelPreflightRejectionCode;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves one exact Agent execution configuration and fails before AgentScope on any mismatch. */
public final class AgentExecutionConfigurationResolver {

    private final AgentModelDefaultRepository defaults;
    private final ModelProviderDefinitionRepository providers;
    private final ModelConnectionRepository connections;
    private final ModelCatalogEntryRepository catalogs;
    private final ModelPriceScheduleRepository prices;
    private final ModelConnectionAvailabilityVerifier connectionAvailability;

    public AgentExecutionConfigurationResolver(
            AgentModelDefaultRepository defaults,
            ModelProviderDefinitionRepository providers,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices) {
        this(
                defaults,
                providers,
                connections,
                catalogs,
                prices,
                ModelConnectionAvailabilityVerifier.persistedStateOnly());
    }

    public AgentExecutionConfigurationResolver(
            AgentModelDefaultRepository defaults,
            ModelProviderDefinitionRepository providers,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionAvailabilityVerifier connectionAvailability) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.connectionAvailability = Objects.requireNonNull(
                connectionAvailability, "connectionAvailability");
    }

    /**
     * Uses only server-owned scope facts. Primary and Fallback are each resolved through the full
     * provider, catalog, connection, policy, authorization and price preflight.
     */
    public ResolvedAgentExecutionConfiguration resolve(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentConfigurationVersion configuration,
            AgentExecutionScopeFacts scopeFacts,
            AgentModelPolicyConstraints policyConstraints,
            AgentExecutionAuthorizationFacts authorization,
            UtcTimestamp resolvedAt) {
        AgentProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        AgentTemplateDefinition requiredTemplate = Objects.requireNonNull(template, "template");
        AgentConfigurationVersion requiredConfiguration = Objects.requireNonNull(
                configuration, "configuration");
        AgentExecutionScope executionScope = AgentExecutionScopePolicy.resolve(scopeFacts);
        AgentModelPolicyConstraints effectivePolicy = mergeTemplateCapabilities(
                Objects.requireNonNull(policyConstraints, "policyConstraints"),
                requiredTemplate,
                requiredConfiguration);

        AgentExecutionModelBinding configuredBinding = bindingFor(
                requiredConfiguration, executionScope);
        BindingResolution binding = resolveBinding(
                requiredProfile, requiredTemplate, executionScope, configuredBinding);

        ResolvedModelSelection primary = resolveSelection(
                ResolvedModelRole.PRIMARY,
                binding.modelBinding().primary(),
                requiredConfiguration,
                executionScope,
                effectivePolicy,
                authorization,
                resolvedAt);
        Optional<ResolvedModelSelection> fallback = binding.modelBinding()
                .fallback()
                .map(selection -> resolveSelection(
                        ResolvedModelRole.FALLBACK,
                        selection,
                        requiredConfiguration,
                        executionScope,
                        effectivePolicy,
                        authorization,
                        resolvedAt));

        return ResolvedAgentExecutionConfiguration.resolve(
                requiredProfile,
                requiredTemplate,
                requiredConfiguration,
                executionScope,
                binding.source(),
                binding.modelDefault(),
                primary,
                fallback);
    }

    private AgentExecutionModelBinding bindingFor(
            AgentConfigurationVersion configuration, AgentExecutionScope executionScope) {
        Optional<AgentExecutionModelBinding> selected = executionScope == AgentExecutionScope.PERSONAL
                ? configuration.personalModelBinding()
                : configuration.teamModelBinding();
        AgentExecutionModelBinding binding = selected.orElseThrow(() -> rejected(
                AgentModelPreflightRejectionCode.MODEL_BINDING_MISSING));
        if (binding.executionScope() != executionScope) {
            throw rejected(AgentModelPreflightRejectionCode.EXECUTION_SCOPE_UNSUPPORTED);
        }
        if (binding.kind() == AgentModelBindingKind.ORCHESTRATION_ONLY) {
            throw rejected(AgentModelPreflightRejectionCode.ORCHESTRATION_ONLY);
        }
        return binding;
    }

    private BindingResolution resolveBinding(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentExecutionScope executionScope,
            AgentExecutionModelBinding binding) {
        if (binding.kind() == AgentModelBindingKind.DIRECT) {
            return new BindingResolution(
                    AgentModelBindingSource.DIRECT,
                    Optional.empty(),
                    binding.directBinding().orElseThrow());
        }
        if (executionScope != AgentExecutionScope.TEAM
                || binding.kind() != AgentModelBindingKind.INHERIT_TEAM_DEFAULT) {
            throw rejected(AgentModelPreflightRejectionCode.MODEL_BINDING_MISSING);
        }

        Optional<io.crewscope.domain.shared.id.TeamId> teamId = profile.scope().teamId();
        if (teamId.isPresent()) {
            AgentModelDefaultScope teamScope = AgentModelDefaultScope.team(
                    profile.scope().organizationId(), teamId.orElseThrow());
            List<AgentModelDefault> teamCandidates = defaults.findCurrentCandidates(
                    teamScope, template.templateVersion(), executionScope);
            Optional<AgentModelDefault> teamDefault = requireUnique(teamCandidates);
            if (teamDefault.isPresent()) {
                requireDefaultCoordinate(
                        teamDefault.orElseThrow(), teamScope, template, executionScope);
                return new BindingResolution(
                        AgentModelBindingSource.TEAM_DEFAULT,
                        teamDefault,
                        teamDefault.orElseThrow().modelBinding());
            }
        }

        AgentModelDefaultScope organizationScope = AgentModelDefaultScope.organization(
                profile.scope().organizationId());
        Optional<AgentModelDefault> organizationDefault = requireUnique(
                defaults.findCurrentCandidates(
                        organizationScope, template.templateVersion(), executionScope));
        AgentModelDefault selected = organizationDefault.orElseThrow(() -> rejected(
                AgentModelPreflightRejectionCode.DEFAULT_MISSING));
        requireDefaultCoordinate(selected, organizationScope, template, executionScope);
        return new BindingResolution(
                AgentModelBindingSource.ORGANIZATION_DEFAULT,
                Optional.of(selected),
                selected.modelBinding());
    }

    private Optional<AgentModelDefault> requireUnique(List<AgentModelDefault> candidates) {
        List<AgentModelDefault> required = List.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
        if (required.size() > 1) {
            throw rejected(AgentModelPreflightRejectionCode.DEFAULT_AMBIGUOUS);
        }
        return required.stream().findFirst();
    }

    private static void requireDefaultCoordinate(
            AgentModelDefault modelDefault,
            AgentModelDefaultScope expectedScope,
            AgentTemplateDefinition template,
            AgentExecutionScope executionScope) {
        AgentModelDefault required = Objects.requireNonNull(modelDefault, "modelDefault");
        if (!required.scope().equals(expectedScope)
                || !required.templateVersion().equals(template.templateVersion())
                || !required.templateContentHash().equals(template.contentHash())
                || required.executionScope() != executionScope) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
    }

    private ResolvedModelSelection resolveSelection(
            ResolvedModelRole role,
            AgentModelSelection selection,
            AgentConfigurationVersion configuration,
            AgentExecutionScope executionScope,
            AgentModelPolicyConstraints policy,
            AgentExecutionAuthorizationFacts authorization,
            UtcTimestamp resolvedAt) {
        ModelProviderDefinition provider = providers.findByKey(selection.providerKey())
                .orElseThrow(() -> rejected(
                        AgentModelPreflightRejectionCode.PROVIDER_UNAVAILABLE));
        ModelConnection connection = connections.findById(
                        selection.organizationId(), selection.connectionId())
                .orElseThrow(() -> rejected(
                        AgentModelPreflightRejectionCode.CONNECTION_UNAVAILABLE));
        ModelCatalogEntry catalog = catalogs.findByCoordinate(selection.catalogCoordinate())
                .orElseThrow(() -> rejected(
                        AgentModelPreflightRejectionCode.CATALOG_UNAVAILABLE));
        ModelPriceRevision price = prices.findEffectivePrice(
                        selection.catalogCoordinate(),
                        Objects.requireNonNull(resolvedAt, "resolvedAt"))
                .orElseThrow(() -> rejected(
                        AgentModelPreflightRejectionCode.PRICE_UNAVAILABLE));
        connectionAvailability.requireAvailable(
                connection, authorization.requestingPrincipalId(), resolvedAt);
        return ResolvedModelSelection.resolve(
                role,
                selection,
                provider,
                connection,
                catalog,
                price,
                configuration.ownership(),
                configuration.ownerUserPrincipalId(),
                executionScope,
                policy,
                authorization);
    }

    private static AgentModelPolicyConstraints mergeTemplateCapabilities(
            AgentModelPolicyConstraints policy,
            AgentTemplateDefinition template,
            AgentConfigurationVersion configuration) {
        return policy.withTemplateRequirements(template, configuration.generateOptions());
    }

    private static AgentModelPreflightException rejected(
            AgentModelPreflightRejectionCode reason) {
        return new AgentModelPreflightException(reason);
    }

    private record BindingResolution(
            AgentModelBindingSource source,
            Optional<AgentModelDefault> modelDefault,
            io.crewscope.domain.agent.AgentDirectModelBinding modelBinding) {}
}
