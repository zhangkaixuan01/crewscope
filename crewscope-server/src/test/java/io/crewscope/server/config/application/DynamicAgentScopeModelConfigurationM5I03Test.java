package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.Model;
import io.crewscope.agentscope.model.AgentScopeModelAdapterRegistry;
import io.crewscope.agentscope.model.AgentScopeModelFactory;
import io.crewscope.agentscope.model.OpenAiAgentScopeModelProviderAdapter;
import io.crewscope.agentscope.model.OpenAiCompatibleAgentScopeModelProviderAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DynamicAgentScopeModelConfigurationM5I03Test {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(DynamicAgentScopeModelConfiguration.class);

  @Test
  void wiresTrustedAdaptersAndFactoryWithoutPublishingDynamicModelsAsBeans() {
    contextRunner.run(context -> assertThat(context)
        .hasNotFailed()
        .hasSingleBean(OpenAiCompatibleAgentScopeModelProviderAdapter.class)
        .hasSingleBean(OpenAiAgentScopeModelProviderAdapter.class)
        .hasSingleBean(AgentScopeModelAdapterRegistry.class)
        .hasSingleBean(AgentScopeModelFactory.class)
        .doesNotHaveBean(Model.class));
  }

  @Test
  void rejectsUnsafeCacheAndRetryBoundsAtStartup() {
    contextRunner
        .withPropertyValues(
            "crewscope.model.dynamic.maximum-cache-entries=0",
            "crewscope.model.dynamic.retry-initial-backoff=30s",
            "crewscope.model.dynamic.retry-maximum-backoff=1s")
        .run(context -> assertThat(context).hasFailed());
  }
}
