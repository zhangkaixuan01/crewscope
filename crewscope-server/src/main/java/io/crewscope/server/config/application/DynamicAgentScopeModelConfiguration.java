package io.crewscope.server.config.application;

import io.crewscope.agentscope.model.AgentScopeModelAdapterRegistry;
import io.crewscope.agentscope.model.AgentScopeModelFactory;
import io.crewscope.agentscope.model.AgentScopeModelProviderAdapter;
import io.crewscope.agentscope.model.OpenAiAgentScopeModelProviderAdapter;
import io.crewscope.agentscope.model.OpenAiCompatibleAgentScopeModelProviderAdapter;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring for trusted dynamic models; no connection-scoped Model is a Spring bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamicAgentScopeModelProperties.class)
public class DynamicAgentScopeModelConfiguration {

  @Bean
  OpenAiCompatibleAgentScopeModelProviderAdapter openAiCompatibleAgentScopeModelProviderAdapter(
      DynamicAgentScopeModelProperties properties) {
    return new OpenAiCompatibleAgentScopeModelProviderAdapter(
        properties.validatedRequestTimeout(),
        properties.validatedRetryInitialBackoff(),
        properties.validatedRetryMaximumBackoff());
  }

  @Bean
  OpenAiAgentScopeModelProviderAdapter openAiAgentScopeModelProviderAdapter(
      DynamicAgentScopeModelProperties properties) {
    return new OpenAiAgentScopeModelProviderAdapter(
        properties.validatedRequestTimeout(),
        properties.validatedRetryInitialBackoff(),
        properties.validatedRetryMaximumBackoff());
  }

  @Bean
  AgentScopeModelAdapterRegistry agentScopeModelAdapterRegistry(
      List<AgentScopeModelProviderAdapter> adapters) {
    return new AgentScopeModelAdapterRegistry(adapters);
  }

  @Bean
  AgentScopeModelFactory agentScopeModelFactory(
      AgentScopeModelAdapterRegistry registry, DynamicAgentScopeModelProperties properties) {
    Duration requestTimeout = properties.validatedRequestTimeout();
    Duration initialBackoff = properties.validatedRetryInitialBackoff();
    Duration maximumBackoff = properties.validatedRetryMaximumBackoff();
    return new AgentScopeModelFactory(
        registry,
        properties.validatedCacheTtl(),
        properties.validatedMaximumCacheEntries(),
        requestTimeout,
        initialBackoff,
        maximumBackoff);
  }
}
