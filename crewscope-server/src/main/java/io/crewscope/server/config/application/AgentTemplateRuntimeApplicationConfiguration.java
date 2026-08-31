package io.crewscope.server.config.application;

import io.agentscope.core.state.AgentStateStore;
import io.crewscope.agentscope.PlatformAgentMiddlewareSet;
import io.crewscope.agentscope.coding.CodingSpecialistFactory;
import io.crewscope.agentscope.model.AgentScopeModelFactory;
import io.crewscope.agentscope.model.ResolvedAgentScopeModelFactory;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.RestrictedTemplateAgentBuilder;
import io.crewscope.agentscope.template.TemplateAgentRuntimeFactory;
import io.crewscope.agentscope.template.TemplatePersonalAgentFactory;
import io.crewscope.agentscope.template.TemplateSpecialistAgentFactory;
import io.crewscope.agentscope.template.TemplateTeamAgentFactory;
import io.crewscope.agentscope.teamobserver.TeamObserverRuntimeContextMiddleware;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Constructor-based Spring composition for exact TemplateVersion Agent runtimes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TemplateAgentRuntimeProperties.class)
public class AgentTemplateRuntimeApplicationConfiguration {

  @Bean
  ResolvedAgentScopeModelFactory resolvedAgentScopeModelFactory(
      ModelProviderDefinitionRepository providers,
      ModelConnectionRepository connections,
      ModelCatalogEntryRepository catalogs,
      ModelConnectionCredentialService credentials,
      AgentScopeModelFactory models) {
    return new ResolvedAgentScopeModelFactory(
        providers, connections, catalogs, credentials, models);
  }

  @Bean
  AgentTemplateRuntimeAssembler agentTemplateRuntimeAssembler(
      ResolvedAgentScopeModelFactory models) {
    return new AgentTemplateRuntimeAssembler(models);
  }

  @Bean
  TeamObserverRuntimeContextMiddleware teamObserverRuntimeContextMiddleware() {
    return new TeamObserverRuntimeContextMiddleware();
  }

  @Bean
  RestrictedTemplateAgentBuilder restrictedTemplateAgentBuilder(
      AgentStateStore stateStore,
      PlatformAgentMiddlewareSet middlewareSet,
      TeamObserverRuntimeContextMiddleware teamObserverMiddleware,
      TemplateAgentRuntimeProperties properties) {
    return new RestrictedTemplateAgentBuilder(
        stateStore,
        properties.validatedRuntimeRoot(),
        properties.validatedMaximumIterations(),
        middlewareSet,
        teamObserverMiddleware);
  }

  @Bean
  TemplatePersonalAgentFactory templatePersonalAgentFactory(
      RestrictedTemplateAgentBuilder builder) {
    return new TemplatePersonalAgentFactory(builder);
  }

  @Bean
  TemplateTeamAgentFactory templateTeamAgentFactory(RestrictedTemplateAgentBuilder builder) {
    return new TemplateTeamAgentFactory(builder);
  }

  @Bean
  TemplateSpecialistAgentFactory templateSpecialistAgentFactory(
      RestrictedTemplateAgentBuilder builder,
      ObjectProvider<CodingSpecialistFactory> codingFactories) {
    Optional<CodingSpecialistFactory> codingFactory = Optional.ofNullable(
        codingFactories.getIfAvailable());
    return new TemplateSpecialistAgentFactory(builder, codingFactory);
  }

  @Bean
  AgentTemplateRuntimeRegistry agentTemplateRuntimeRegistry(
      List<TemplateAgentRuntimeFactory> factories) {
    return new AgentTemplateRuntimeRegistry(factories);
  }
}
