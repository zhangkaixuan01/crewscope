package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModelPreflightApplicationConfigurationM5I04Test {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelPreflightApplicationConfiguration.class)
            .withBean(CredentialStore.class, () -> mock(CredentialStore.class))
            .withBean(AgentModelDefaultRepository.class, () -> mock(AgentModelDefaultRepository.class))
            .withBean(ModelProviderDefinitionRepository.class, () -> mock(ModelProviderDefinitionRepository.class))
            .withBean(ModelConnectionRepository.class, () -> mock(ModelConnectionRepository.class))
            .withBean(ModelCatalogEntryRepository.class, () -> mock(ModelCatalogEntryRepository.class))
            .withBean(ModelPriceScheduleRepository.class, () -> mock(ModelPriceScheduleRepository.class))
            .withBean(AgentProfileRepository.class, () -> mock(AgentProfileRepository.class))
            .withBean(AgentTemplateRepository.class, () -> mock(AgentTemplateRepository.class))
            .withBean(AgentConfigurationRepository.class, () -> mock(AgentConfigurationRepository.class))
            .withBean(PolicySnapshotRepository.class, () -> mock(PolicySnapshotRepository.class));

    @Test
    void wiresOneTrustedPreflightGraphAndSchemaV2SnapshotAssembler() {
        runner.run(context -> context.assertThat()
                .hasNotFailed()
                .hasSingleBean(CachedModelConnectionAvailabilityVerifier.class)
                .hasSingleBean(ModelConnectionAvailabilityVerifier.class)
                .hasSingleBean(AgentExecutionConfigurationResolver.class)
                .hasSingleBean(AgentExecutionConfigurationService.class)
                .hasSingleBean(SelectableModelCatalogService.class)
                .hasSingleBean(ResolvedAgentPolicySnapshotService.class));
    }

    @Test
    void defaultsCatalogPageToThePersistenceAdapterLimit() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            assertThat(context.getBean(ModelPreflightProperties.class)
                    .validatedMaximumCatalogEntriesPerProvider())
                    .isEqualTo(200);
        });
    }

    @Test
    void rejectsUnsafeHealthCacheAndCatalogBoundsAtStartup() {
        runner.withPropertyValues(
                        "crewscope.model.preflight.health-cache-ttl=6m",
                        "crewscope.model.preflight.maximum-health-cache-entries=0",
                        "crewscope.model.preflight.maximum-catalog-entries-per-provider=10001")
                .run(context -> context.assertThat().hasFailed());
    }
}
