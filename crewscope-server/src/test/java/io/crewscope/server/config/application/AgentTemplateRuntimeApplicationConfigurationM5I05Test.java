package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.crewscope.agentscope.PlatformAgentMiddlewareSet;
import io.crewscope.agentscope.model.AgentScopeModelFactory;
import io.crewscope.agentscope.model.ResolvedAgentScopeModelFactory;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.RestrictedTemplateAgentBuilder;
import io.crewscope.agentscope.template.TemplatePersonalAgentFactory;
import io.crewscope.agentscope.template.TemplateSpecialistAgentFactory;
import io.crewscope.agentscope.template.TemplateTeamAgentFactory;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Spring composition proof for the M5-I05 exact Template runtime registry. */
class AgentTemplateRuntimeApplicationConfigurationM5I05Test {

  @Test
  void wiresAllThreeRoleFactoriesWithoutRequiringCodingOnAnApiOnlyProcess() {
    PlatformAgentMiddlewareSet middlewareSet = mock(PlatformAgentMiddlewareSet.class);
    when(middlewareSet.ordered()).thenReturn(java.util.List.of());

    new ApplicationContextRunner()
        .withUserConfiguration(AgentTemplateRuntimeApplicationConfiguration.class)
        .withBean(ModelProviderDefinitionRepository.class,
            () -> mock(ModelProviderDefinitionRepository.class))
        .withBean(ModelConnectionRepository.class,
            () -> mock(ModelConnectionRepository.class))
        .withBean(ModelCatalogEntryRepository.class,
            () -> mock(ModelCatalogEntryRepository.class))
        .withBean(ModelConnectionCredentialService.class,
            () -> mock(ModelConnectionCredentialService.class))
        .withBean(AgentScopeModelFactory.class, () -> mock(AgentScopeModelFactory.class))
        .withBean(AgentStateStore.class, () -> mock(AgentStateStore.class))
        .withBean(PlatformAgentMiddlewareSet.class, () -> middlewareSet)
        .run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(ResolvedAgentScopeModelFactory.class)
            .hasSingleBean(AgentTemplateRuntimeAssembler.class)
            .hasSingleBean(RestrictedTemplateAgentBuilder.class)
            .hasSingleBean(TemplatePersonalAgentFactory.class)
            .hasSingleBean(TemplateTeamAgentFactory.class)
            .hasSingleBean(TemplateSpecialistAgentFactory.class)
            .hasSingleBean(AgentTemplateRuntimeRegistry.class));
  }

  @Test
  void rejectsAnUnboundedTemplateIterationLimitAtStartup() {
    PlatformAgentMiddlewareSet middlewareSet = mock(PlatformAgentMiddlewareSet.class);
    when(middlewareSet.ordered()).thenReturn(java.util.List.of());

    new ApplicationContextRunner()
        .withUserConfiguration(AgentTemplateRuntimeApplicationConfiguration.class)
        .withPropertyValues("crewscope.runtime.template-agent.maximum-iterations=201")
        .withBean(ModelProviderDefinitionRepository.class,
            () -> mock(ModelProviderDefinitionRepository.class))
        .withBean(ModelConnectionRepository.class,
            () -> mock(ModelConnectionRepository.class))
        .withBean(ModelCatalogEntryRepository.class,
            () -> mock(ModelCatalogEntryRepository.class))
        .withBean(ModelConnectionCredentialService.class,
            () -> mock(ModelConnectionCredentialService.class))
        .withBean(AgentScopeModelFactory.class, () -> mock(AgentScopeModelFactory.class))
        .withBean(AgentStateStore.class, () -> mock(AgentStateStore.class))
        .withBean(PlatformAgentMiddlewareSet.class, () -> middlewareSet)
        .run(context -> assertThat(context).hasFailed());
  }
}
