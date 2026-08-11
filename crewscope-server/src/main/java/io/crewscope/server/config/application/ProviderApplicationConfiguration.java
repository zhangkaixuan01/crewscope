package io.crewscope.server.config.application;

import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.BuiltInProviderInitializationService;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBindingQueryService;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.integration.provider.workitem.NativeWorkItemProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free Provider resolution use case to current persistence facts. */
@Configuration(proxyBeanMethods = false)
public class ProviderApplicationConfiguration {

  @Bean
  NativeWorkItemProvider nativeWorkItemProvider() {
    return new NativeWorkItemProvider();
  }

  @Bean
  BuiltInProviderRegistration nativeWorkItemProviderRegistration(
      NativeWorkItemProvider provider) {
    return provider.registration();
  }

  @Bean
  ProviderBindingResolver providerBindingResolver(
      ProviderBindingRepository bindingRepository,
      ProviderDefinitionRepository definitionRepository,
      ProviderImplementationRepository implementationRepository,
      ConnectionRepository connectionRepository,
      ConnectionGrantRepository connectionGrantRepository,
      TimeProvider timeProvider) {
    return new ProviderBindingResolver(
        bindingRepository,
        definitionRepository,
        implementationRepository,
        connectionRepository,
        connectionGrantRepository,
        timeProvider);
  }

  @Bean
  BuiltInProviderInitializationService builtInProviderInitializationService(
      BuiltInProviderRegistration registration,
      ProviderDefinitionRepository definitionRepository,
      ProviderImplementationRepository implementationRepository,
      ProviderBindingRepository bindingRepository,
      ProviderBootstrapLock bootstrapLock,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    return new BuiltInProviderInitializationService(
        registration,
        definitionRepository,
        implementationRepository,
        bindingRepository,
        bootstrapLock,
        transactionExecutor,
        timeProvider);
  }

  @Bean
  io.crewscope.application.provider.TeamProviderInitializer teamProviderInitializer(
      BuiltInProviderInitializationService initializer) {
    return initializer::initialize;
  }

  @Bean
  ProviderBindingQueryService providerBindingQueryService(
      BuiltInProviderRegistration registration,
      TeamRepository teamRepository,
      WorkspaceRepository workspaceRepository,
      TeamMembershipQuery membershipQuery,
      ProviderBindingResolver resolver,
      TransactionExecutor transactionExecutor) {
    return new ProviderBindingQueryService(
        registration,
        teamRepository,
        workspaceRepository,
        membershipQuery,
        resolver,
        transactionExecutor);
  }
}
