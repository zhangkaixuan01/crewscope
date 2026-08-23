package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationResolver;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.agent.ResolvedAgentPolicySnapshotService;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.model.CachedModelConnectionAvailabilityVerifier;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionAvailabilityVerifier;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.model.SelectableModelCatalogService;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.team.AgentProfileRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit constructor wiring for model catalog intersection and execution preflight. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelPreflightProperties.class)
public class ModelPreflightApplicationConfiguration {

    @Bean
    CachedModelConnectionAvailabilityVerifier modelConnectionAvailabilityVerifier(
            CredentialStore credentialStore, ModelPreflightProperties properties) {
        return new CachedModelConnectionAvailabilityVerifier(
                credentialStore,
                properties.validatedHealthCacheTtl(),
                properties.validatedMaximumHealthCacheEntries());
    }

    @Bean
    AgentExecutionConfigurationResolver agentExecutionConfigurationResolver(
            AgentModelDefaultRepository defaults,
            ModelProviderDefinitionRepository providers,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionAvailabilityVerifier availability) {
        return new AgentExecutionConfigurationResolver(
                defaults, providers, connections, catalogs, prices, availability);
    }

    @Bean
    AgentExecutionConfigurationService agentExecutionConfigurationService(
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            AgentExecutionConfigurationResolver resolver) {
        return new AgentExecutionConfigurationService(
                profiles, templates, configurations, resolver);
    }

    @Bean
    SelectableModelCatalogService selectableModelCatalogService(
            ModelProviderDefinitionRepository providers,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionAvailabilityVerifier availability,
            ModelPreflightProperties properties) {
        return new SelectableModelCatalogService(
                providers,
                connections,
                catalogs,
                prices,
                availability,
                properties.validatedMaximumCatalogEntriesPerProvider());
    }

    @Bean
    ResolvedAgentPolicySnapshotService resolvedAgentPolicySnapshotService(
            AgentExecutionConfigurationService configurations,
            PolicySnapshotRepository snapshots) {
        return new ResolvedAgentPolicySnapshotService(configurations, snapshots);
    }
}
