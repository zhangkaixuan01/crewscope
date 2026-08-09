package io.crewscope.server.config.application;

import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free Provider resolution use case to current persistence facts. */
@Configuration(proxyBeanMethods = false)
public class ProviderApplicationConfiguration {

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
}
