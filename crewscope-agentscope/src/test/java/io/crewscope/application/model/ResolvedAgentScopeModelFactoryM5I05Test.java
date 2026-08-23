package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import io.crewscope.agentscope.model.AgentScopeFormatterPolicy;
import io.crewscope.agentscope.model.AgentScopeModelBuildException;
import io.crewscope.agentscope.model.AgentScopeModelFactory;
import io.crewscope.agentscope.model.ResolvedAgentScopeModelFactory;
import io.crewscope.agentscope.model.ResolvedAgentScopeModels;
import io.crewscope.agentscope.model.StructuredOutputCompatibility;
import io.crewscope.agentscope.model.TrustedModelBuildRequest;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentReasoningMode;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M5-I05 exact resolved-coordinate to AgentScope model materialization tests. */
class ResolvedAgentScopeModelFactoryM5I05Test {

    @Test
    void mapsDeepSeekAndIssuesOneVersionBoundCredentialHandlePerRole() {
        Fixture fixture = fixture(true, "deepseek");

        ResolvedAgentScopeModels result = fixture.factory().build(
                fixture.organizationId(),
                fixture.resolved(),
                safeOptions(),
                fixture.actor(),
                UUID.randomUUID());

        assertSame(fixture.builtPrimary(), result.primary());
        assertSame(fixture.builtFallback(), result.fallback().orElseThrow());
        ArgumentCaptor<TrustedModelBuildRequest> modelRequests =
                ArgumentCaptor.forClass(TrustedModelBuildRequest.class);
        verify(fixture.models(), times(2)).build(
                modelRequests.capture(), any(ProviderCredentialHandle.class));
        assertEquals(
                List.of(ResolvedModelRole.PRIMARY, ResolvedModelRole.FALLBACK),
                modelRequests.getAllValues().stream().map(TrustedModelBuildRequest::role).toList());
        assertEquals(
                AgentScopeFormatterPolicy.DEEPSEEK,
                modelRequests.getAllValues().get(0).formatterPolicy());
        assertEquals(
                StructuredOutputCompatibility.SYNTHETIC_TOOL,
                modelRequests.getAllValues().get(0).structuredOutputCompatibility());

        ArgumentCaptor<OpenProviderCredentialHandleRequest> handleRequests =
                ArgumentCaptor.forClass(OpenProviderCredentialHandleRequest.class);
        verify(fixture.credentials(), times(2)).openHandle(handleRequests.capture());
        assertEquals(
                List.of(7L, 7L),
                handleRequests.getAllValues().stream()
                        .map(OpenProviderCredentialHandleRequest::expectedConnectionVersion)
                        .toList());
        assertEquals(
                List.of(new ModelCredentialVersion(3), new ModelCredentialVersion(3)),
                handleRequests.getAllValues().stream()
                        .map(OpenProviderCredentialHandleRequest::expectedCredentialVersion)
                        .toList());
    }

    @Test
    void failsClosedBeforeOpeningAHandleWhenTheConnectionVersionDrifts() {
        Fixture fixture = fixture(false, "deepseek");
        when(fixture.connection().version()).thenReturn(8L);

        assertThrows(
                AgentScopeModelBuildException.class,
                () -> fixture.factory().build(
                        fixture.organizationId(),
                        fixture.resolved(),
                        safeOptions(),
                        fixture.actor(),
                        UUID.randomUUID()));
        verify(fixture.credentials(), times(0)).openHandle(any());
    }

    @Test
    void failsClosedBeforeOpeningAHandleWhenTheCredentialVersionDrifts() {
        Fixture fixture = fixture(false, "deepseek");
        ModelCredentialBinding rotated = mock(ModelCredentialBinding.class);
        when(rotated.credentialVersion()).thenReturn(new ModelCredentialVersion(4));
        when(fixture.connection().credentialBinding()).thenReturn(rotated);

        assertThrows(
                AgentScopeModelBuildException.class,
                () -> fixture.factory().build(
                        fixture.organizationId(),
                        fixture.resolved(),
                        safeOptions(),
                        fixture.actor(),
                        UUID.randomUUID()));
        verify(fixture.credentials(), times(0)).openHandle(any());
    }

    @Test
    void usesNativeStructuredOutputForTheOpenAiProtocolAdapter() {
        Fixture fixture = fixture(false, "openai");

        fixture.factory().build(
                fixture.organizationId(),
                fixture.resolved(),
                safeOptions(),
                fixture.actor(),
                UUID.randomUUID());

        ArgumentCaptor<TrustedModelBuildRequest> request =
                ArgumentCaptor.forClass(TrustedModelBuildRequest.class);
        verify(fixture.models()).build(request.capture(), any(ProviderCredentialHandle.class));
        assertEquals(AgentScopeFormatterPolicy.OPENAI, request.getValue().formatterPolicy());
        assertEquals(
                StructuredOutputCompatibility.NATIVE,
                request.getValue().structuredOutputCompatibility());
    }

    private static Fixture fixture(boolean fallback, String providerValue) {
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId actor = PrincipalId.generate();
        ModelProviderKey providerKey = new ModelProviderKey(providerValue);
        ModelAdapterKey adapterKey = new ModelAdapterKey(
                "openai".equals(providerValue) ? "openai" : "openai-compatible");
        ModelRegistryHash providerHash = new ModelRegistryHash("1".repeat(64));
        ModelRegistryHash catalogHash = new ModelRegistryHash("2".repeat(64));
        ModelConnectionId connectionId = ModelConnectionId.generate();
        ModelCredentialVersion credentialVersion = new ModelCredentialVersion(3);
        ModelRegion region = new ModelRegion("global");
        ModelConnectionOwner owner = ModelConnectionOwner.organization(organizationId);
        ModelCatalogCoordinate coordinate = new ModelCatalogCoordinate(
                ModelCatalogEntryId.generate(),
                providerKey,
                new ModelId("openai".equals(providerValue) ? "gpt-5" : "deepseek-v4-flash"),
                new ModelCatalogRevision(4));
        ModelRevision modelRevision = new ModelRevision("2026-08");
        ModelDataPolicy dataPolicy = ModelDataPolicy.noRetention();

        ResolvedModelSelection primary = selection(
                ResolvedModelRole.PRIMARY,
                providerKey,
                providerHash,
                adapterKey,
                dataPolicy,
                connectionId,
                owner,
                credentialVersion,
                region,
                coordinate,
                catalogHash,
                modelRevision);
        ResolvedModelSelection fallbackSelection = selection(
                ResolvedModelRole.FALLBACK,
                providerKey,
                providerHash,
                adapterKey,
                dataPolicy,
                connectionId,
                owner,
                credentialVersion,
                region,
                coordinate,
                catalogHash,
                modelRevision);

        AgentOwnership ownership = mock(AgentOwnership.class);
        when(ownership.organizationId()).thenReturn(organizationId);
        ResolvedAgentExecutionConfiguration resolved =
                mock(ResolvedAgentExecutionConfiguration.class);
        when(resolved.ownership()).thenReturn(ownership);
        when(resolved.primary()).thenReturn(primary);
        when(resolved.fallback()).thenReturn(
                fallback ? Optional.of(fallbackSelection) : Optional.empty());
        when(resolved.configurationHash()).thenReturn(new AgentConfigurationHash("3".repeat(64)));

        ModelProviderDefinition provider = mock(ModelProviderDefinition.class);
        when(provider.providerKey()).thenReturn(providerKey);
        when(provider.contentHash()).thenReturn(providerHash);
        when(provider.adapterKey()).thenReturn(adapterKey);
        when(provider.dataPolicy()).thenReturn(dataPolicy);
        when(provider.status()).thenReturn(ModelRegistryStatus.ACTIVE);
        ModelConnection connection = mock(ModelConnection.class);
        when(connection.organizationId()).thenReturn(organizationId);
        when(connection.id()).thenReturn(connectionId);
        when(connection.version()).thenReturn(7L);
        when(connection.providerKey()).thenReturn(providerKey);
        when(connection.providerDefinitionHash()).thenReturn(providerHash);
        when(connection.owner()).thenReturn(owner);
        when(connection.region()).thenReturn(region);
        when(connection.endpoint()).thenReturn(new ModelEndpoint("https://api.deepseek.com"));
        ModelCredentialBinding credentialBinding = mock(ModelCredentialBinding.class);
        when(credentialBinding.credentialVersion()).thenReturn(credentialVersion);
        when(connection.credentialBinding()).thenReturn(credentialBinding);
        ModelCatalogEntry catalog = mock(ModelCatalogEntry.class);
        when(catalog.coordinate()).thenReturn(coordinate);
        when(catalog.providerKey()).thenReturn(providerKey);
        when(catalog.providerDefinitionHash()).thenReturn(providerHash);
        when(catalog.contentHash()).thenReturn(catalogHash);
        when(catalog.modelRevision()).thenReturn(modelRevision);
        when(catalog.status()).thenReturn(ModelRegistryStatus.ACTIVE);

        ModelProviderDefinitionRepository providers = mock(ModelProviderDefinitionRepository.class);
        when(providers.findByKey(any())).thenReturn(Optional.of(provider));
        ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
        when(connections.findById(organizationId, connectionId)).thenReturn(Optional.of(connection));
        ModelCatalogEntryRepository catalogs = mock(ModelCatalogEntryRepository.class);
        when(catalogs.findByCoordinate(coordinate)).thenReturn(Optional.of(catalog));
        ModelConnectionCredentialService credentials =
                mock(ModelConnectionCredentialService.class);
        when(credentials.openHandle(any())).thenReturn(mock(ProviderCredentialHandle.class));
        AgentScopeModelFactory models = mock(AgentScopeModelFactory.class);
        Model builtPrimary = mock(Model.class);
        Model builtFallback = mock(Model.class);
        when(models.build(any(), any()))
                .thenReturn(builtPrimary)
                .thenReturn(builtFallback);

        return new Fixture(
                organizationId,
                actor,
                primary,
                resolved,
                provider,
                connection,
                catalog,
                credentials,
                models,
                builtPrimary,
                builtFallback,
                new ResolvedAgentScopeModelFactory(
                        providers, connections, catalogs, credentials, models));
    }

    private static ResolvedModelSelection selection(
            ResolvedModelRole role,
            ModelProviderKey providerKey,
            ModelRegistryHash providerHash,
            ModelAdapterKey adapterKey,
            ModelDataPolicy dataPolicy,
            ModelConnectionId connectionId,
            ModelConnectionOwner owner,
            ModelCredentialVersion credentialVersion,
            ModelRegion region,
            ModelCatalogCoordinate coordinate,
            ModelRegistryHash catalogHash,
            ModelRevision modelRevision) {
        ResolvedModelSelection selection = mock(ResolvedModelSelection.class);
        when(selection.role()).thenReturn(role);
        when(selection.providerKey()).thenReturn(providerKey);
        when(selection.providerDefinitionHash()).thenReturn(providerHash);
        when(selection.adapterKey()).thenReturn(adapterKey);
        when(selection.dataPolicy()).thenReturn(dataPolicy);
        when(selection.connectionId()).thenReturn(connectionId);
        when(selection.connectionVersion()).thenReturn(7L);
        when(selection.connectionOwner()).thenReturn(owner);
        when(selection.credentialVersion()).thenReturn(credentialVersion);
        when(selection.region()).thenReturn(region);
        when(selection.catalogCoordinate()).thenReturn(coordinate);
        when(selection.catalogContentHash()).thenReturn(catalogHash);
        when(selection.modelRevision()).thenReturn(modelRevision);
        return selection;
    }

    private static SafeModelGenerateOptions safeOptions() {
        return new SafeModelGenerateOptions(
                Optional.empty(),
                Optional.empty(),
                Optional.of(4_096L),
                AgentReasoningMode.DEFAULT,
                false,
                false,
                Optional.empty(),
                2);
    }

    private record Fixture(
            OrganizationId organizationId,
            PrincipalId actor,
            ResolvedModelSelection selection,
            ResolvedAgentExecutionConfiguration resolved,
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog,
            ModelConnectionCredentialService credentials,
            AgentScopeModelFactory models,
            Model builtPrimary,
            Model builtFallback,
            ResolvedAgentScopeModelFactory factory) {}
}
