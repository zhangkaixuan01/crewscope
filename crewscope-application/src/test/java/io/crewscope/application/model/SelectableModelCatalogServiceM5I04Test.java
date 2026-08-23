package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealth;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectableModelCatalogServiceM5I04Test {

    @Test
    void returnsOnlyThePermissionPolicyCapabilityHealthAndPriceIntersection() {
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId actor = PrincipalId.generate();
        ModelProviderKey providerKey = new ModelProviderKey("deepseek");
        ModelRegistryHash providerHash = new ModelRegistryHash("1".repeat(64));
        ModelRegion region = new ModelRegion("global");
        ModelConnectionId connectionId = ModelConnectionId.generate();
        ModelCredentialVersion credentialVersion = new ModelCredentialVersion(2);
        ModelConnection connection = mock(ModelConnection.class);
        ModelConnectionHealth health = mock(ModelConnectionHealth.class);
        ModelConnectionOwner owner = ModelConnectionOwner.organization(organizationId);
        when(connection.organizationId()).thenReturn(organizationId);
        when(connection.id()).thenReturn(connectionId);
        when(connection.version()).thenReturn(4L);
        when(connection.owner()).thenReturn(owner);
        when(connection.providerKey()).thenReturn(providerKey);
        when(connection.providerDefinitionHash()).thenReturn(providerHash);
        when(connection.region()).thenReturn(region);
        when(connection.status()).thenReturn(ModelConnectionStatus.ACTIVE);
        when(connection.health()).thenReturn(health);
        when(health.status()).thenReturn(ModelConnectionHealthStatus.HEALTHY);
        when(health.isHealthyFor(credentialVersion)).thenReturn(true);
        when(connection.credentialBinding()).thenReturn(new ModelCredentialBinding(
                CredentialId.generate(),
                ModelCredentialSubject.organization(organizationId),
                credentialVersion));

        ModelProviderDefinition provider = mock(ModelProviderDefinition.class);
        when(provider.providerKey()).thenReturn(providerKey);
        when(provider.contentHash()).thenReturn(providerHash);
        when(provider.adapterKey()).thenReturn(new ModelAdapterKey("openai-compatible"));
        when(provider.dataPolicy()).thenReturn(ModelDataPolicy.noRetention());
        when(provider.availableRegions()).thenReturn(Set.of(region));
        when(provider.status()).thenReturn(ModelRegistryStatus.ACTIVE);
        when(provider.displayName()).thenReturn("DeepSeek");

        ModelCatalogEntry eligible = catalog(
                providerKey,
                providerHash,
                region,
                "deepseek-v4-flash",
                Set.of(new ModelCapability("tool-calling")),
                "2".repeat(64));
        ModelCatalogEntry missingCapability = catalog(
                providerKey,
                providerHash,
                region,
                "deepseek-chat",
                Set.of(new ModelCapability("text")),
                "3".repeat(64));
        ModelPriceRevision eligiblePrice = price(eligible, "4".repeat(64));
        ModelPriceRevision excludedPrice = price(missingCapability, "5".repeat(64));

        ModelProviderDefinitionRepository providers = mock(ModelProviderDefinitionRepository.class);
        ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
        ModelCatalogEntryRepository catalogs = mock(ModelCatalogEntryRepository.class);
        ModelPriceScheduleRepository prices = mock(ModelPriceScheduleRepository.class);
        when(providers.findByKey(providerKey)).thenReturn(Optional.of(provider));
        when(connections.findById(organizationId, connectionId))
                .thenReturn(Optional.of(connection));
        when(catalogs.findPage(providerKey, 0, 100)).thenReturn(List.of(eligible, missingCapability));
        UtcTimestamp now = UtcTimestamp.parse("2026-08-23T08:00:00Z");
        when(prices.findEffectivePrice(eligible.coordinate(), now))
                .thenReturn(Optional.of(eligiblePrice));
        when(prices.findEffectivePrice(missingCapability.coordinate(), now))
                .thenReturn(Optional.of(excludedPrice));

        AgentTemplateDefinition template = mock(AgentTemplateDefinition.class);
        AgentTemplateCapabilities templateCapabilities = mock(AgentTemplateCapabilities.class);
        when(template.capabilities()).thenReturn(templateCapabilities);
        when(templateCapabilities.requiredModelCapabilities()).thenReturn(
                Set.of(new AgentTemplateCapability("model.tool-calling")));
        AgentExecutionAuthorizationFacts authorization = new AgentExecutionAuthorizationFacts(
                actor, true, true, true, true, true, Set.of(connectionId));
        AgentModelPolicyConstraints policy = new AgentModelPolicyConstraints(
                Set.of(),
                Set.of(region),
                Set.of(ModelDataRetentionMode.NONE),
                Optional.of(Duration.ofDays(1)),
                false,
                16_000,
                2_048);
        SelectableModelCatalogService service = new SelectableModelCatalogService(
                providers,
                connections,
                catalogs,
                prices,
                ModelConnectionAvailabilityVerifier.persistedStateOnly(),
                100);

        List<SelectableModelOption> result = service.findSelectable(
                new SelectableModelCatalogQuery(
                        organizationId,
                        AgentOwnership.organization(organizationId),
                        Optional.empty(),
                        AgentExecutionScope.PERSONAL,
                        template,
                        SafeModelGenerateOptions.defaults(),
                        policy,
                        Set.of(providerKey),
                        Set.of(eligible.coordinate(), missingCapability.coordinate()),
                        authorization,
                        now));

        assertEquals(1, result.size());
        assertEquals(eligible.coordinate(), result.get(0).selection().catalogCoordinate());
        assertEquals("DeepSeek", result.get(0).providerDisplayName());
    }

    private static ModelCatalogEntry catalog(
            ModelProviderKey providerKey,
            ModelRegistryHash providerHash,
            ModelRegion region,
            String modelId,
            Set<ModelCapability> capabilities,
            String contentHash) {
        ModelCatalogEntry value = mock(ModelCatalogEntry.class);
        ModelCatalogCoordinate coordinate = new ModelCatalogCoordinate(
                ModelCatalogEntryId.generate(),
                providerKey,
                new ModelId(modelId),
                new ModelCatalogRevision(1));
        when(value.coordinate()).thenReturn(coordinate);
        when(value.providerKey()).thenReturn(providerKey);
        when(value.providerDefinitionHash()).thenReturn(providerHash);
        when(value.contentHash()).thenReturn(new ModelRegistryHash(contentHash));
        when(value.modelRevision()).thenReturn(new ModelRevision("2026-08"));
        when(value.displayName()).thenReturn(modelId);
        when(value.contextWindowTokens()).thenReturn(64_000L);
        when(value.maximumOutputTokens()).thenReturn(8_192L);
        when(value.capabilities()).thenReturn(capabilities);
        when(value.availableRegions()).thenReturn(Set.of(region));
        when(value.status()).thenReturn(ModelRegistryStatus.ACTIVE);
        return value;
    }

    private static ModelPriceRevision price(ModelCatalogEntry catalog, String hash) {
        ModelPriceRevision value = mock(ModelPriceRevision.class);
        ModelCatalogCoordinate coordinate = catalog.coordinate();
        when(value.catalogCoordinate()).thenReturn(coordinate);
        when(value.revision()).thenReturn(1L);
        when(value.tokenPrice()).thenReturn(new ModelTokenPrice(
                new BigDecimal("1"),
                new BigDecimal("2"),
                Optional.empty(),
                "USD"));
        when(value.contentHash()).thenReturn(new ModelRegistryHash(hash));
        return value;
    }
}
