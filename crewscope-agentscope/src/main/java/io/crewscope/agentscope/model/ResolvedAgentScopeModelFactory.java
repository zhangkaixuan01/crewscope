package io.crewscope.agentscope.model;

import io.agentscope.core.model.Model;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.model.OpenProviderCredentialHandleRequest;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Materializes exact preflighted model coordinates and closes credential rotation races. */
public final class ResolvedAgentScopeModelFactory {

    private static final String OPENAI_COMPATIBLE = "openai-compatible";
    private static final String OPENAI = "openai";
    private static final String DEEPSEEK = "deepseek";
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final ModelProviderDefinitionRepository providers;
    private final ModelConnectionRepository connections;
    private final ModelCatalogEntryRepository catalogs;
    private final ModelConnectionCredentialService credentials;
    private final AgentScopeModelFactory models;

    public ResolvedAgentScopeModelFactory(
            ModelProviderDefinitionRepository providers,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            ModelConnectionCredentialService credentials,
            AgentScopeModelFactory models) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.models = Objects.requireNonNull(models, "models");
    }

    public ResolvedAgentScopeModels build(
            OrganizationId organizationId,
            ResolvedAgentExecutionConfiguration configuration,
            SafeModelGenerateOptions generateOptions,
            PrincipalId actor,
            UUID correlationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        ResolvedAgentExecutionConfiguration resolved = Objects.requireNonNull(
                configuration, "configuration");
        SafeModelGenerateOptions options = Objects.requireNonNull(generateOptions, "generateOptions");
        PrincipalId requestingPrincipal = Objects.requireNonNull(actor, "actor");
        UUID correlation = Objects.requireNonNull(correlationId, "correlationId");
        if (!resolved.ownership().organizationId().equals(organization)) {
            throw invalidRequest();
        }
        Model primary = buildSelection(
                organization,
                resolved.primary(),
                options,
                resolved.configurationHash(),
                requestingPrincipal,
                correlation);
        Optional<Model> fallback = resolved.fallback().map(selection -> buildSelection(
                organization,
                selection,
                options,
                resolved.configurationHash(),
                requestingPrincipal,
                correlation));
        return new ResolvedAgentScopeModels(primary, fallback);
    }

    private Model buildSelection(
            OrganizationId organizationId,
            ResolvedModelSelection selection,
            SafeModelGenerateOptions generateOptions,
            io.crewscope.domain.agent.AgentConfigurationHash compatibilityHash,
            PrincipalId actor,
            UUID correlationId) {
        ModelProviderDefinition provider = providers.findByKey(selection.providerKey())
                .orElseThrow(ResolvedAgentScopeModelFactory::invalidRequest);
        ModelConnection connection = connections.findById(organizationId, selection.connectionId())
                .orElseThrow(ResolvedAgentScopeModelFactory::invalidRequest);
        ModelCatalogEntry catalog = catalogs.findByCoordinate(selection.catalogCoordinate())
                .orElseThrow(ResolvedAgentScopeModelFactory::invalidRequest);
        requireExactCoordinate(selection, provider, connection, catalog, organizationId);
        Compatibility compatibility = compatibility(provider);
        TrustedModelBuildRequest request = new TrustedModelBuildRequest(
                organizationId,
                selection.role(),
                selection.providerKey(),
                selection.providerDefinitionHash(),
                selection.adapterKey(),
                selection.connectionId(),
                selection.connectionVersion(),
                selection.credentialVersion(),
                connection.endpoint(),
                CHAT_COMPLETIONS_PATH,
                selection.catalogCoordinate(),
                selection.catalogContentHash(),
                selection.modelRevision(),
                compatibility.formatter(),
                compatibility.structuredOutput(),
                generateOptions,
                compatibilityHash);
        return models.build(
                request,
                credentials.openHandle(new OpenProviderCredentialHandleRequest(
                        organizationId,
                        selection.connectionId(),
                        selection.connectionVersion(),
                        selection.credentialVersion(),
                        actor,
                        "model:agent-template:" + selection.role().name().toLowerCase(java.util.Locale.ROOT),
                        correlationId)));
    }

    private static void requireExactCoordinate(
            ResolvedModelSelection selection,
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog,
            OrganizationId organizationId) {
        boolean exact = provider.status() == ModelRegistryStatus.ACTIVE
                && provider.providerKey().equals(selection.providerKey())
                && provider.contentHash().equals(selection.providerDefinitionHash())
                && provider.adapterKey().equals(selection.adapterKey())
                && provider.dataPolicy().equals(selection.dataPolicy())
                && connection.organizationId().equals(organizationId)
                && connection.id().equals(selection.connectionId())
                && connection.version() == selection.connectionVersion()
                && connection.providerKey().equals(selection.providerKey())
                && connection.providerDefinitionHash().equals(selection.providerDefinitionHash())
                && connection.owner().equals(selection.connectionOwner())
                && connection.region().equals(selection.region())
                && connection.credentialBinding().credentialVersion().equals(selection.credentialVersion())
                && catalog.status() == ModelRegistryStatus.ACTIVE
                && catalog.coordinate().equals(selection.catalogCoordinate())
                && catalog.providerKey().equals(selection.providerKey())
                && catalog.providerDefinitionHash().equals(selection.providerDefinitionHash())
                && catalog.contentHash().equals(selection.catalogContentHash())
                && catalog.modelRevision().equals(selection.modelRevision());
        if (!exact) {
            throw invalidRequest();
        }
    }

    private static Compatibility compatibility(ModelProviderDefinition provider) {
        String providerKey = provider.providerKey().value();
        String adapterKey = provider.adapterKey().value();
        if (DEEPSEEK.equals(providerKey)) {
            if (!OPENAI_COMPATIBLE.equals(adapterKey)) {
                throw invalidRequest();
            }
            return new Compatibility(
                    AgentScopeFormatterPolicy.DEEPSEEK,
                    StructuredOutputCompatibility.SYNTHETIC_TOOL);
        }
        if (OPENAI.equals(adapterKey) || OPENAI_COMPATIBLE.equals(adapterKey)) {
            return new Compatibility(
                    AgentScopeFormatterPolicy.OPENAI,
                    StructuredOutputCompatibility.NATIVE);
        }
        throw invalidRequest();
    }

    private static AgentScopeModelBuildException invalidRequest() {
        return new AgentScopeModelBuildException(
                AgentScopeModelBuildException.Code.INVALID_TRUSTED_REQUEST);
    }

    private record Compatibility(
            AgentScopeFormatterPolicy formatter,
            StructuredOutputCompatibility structuredOutput) {}
}
