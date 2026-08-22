package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.agentscope.core.model.Model;
import io.agentscope.spring.boot.openai.OpenAIAutoConfiguration;
import io.crewscope.agentscope.AgentScopeModelResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentScopeModelConfigurationM4Q03Test {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(AgentScopeModelConfiguration.class);

  @Test
  void resolvesStablePrimarySlotToTheUniqueSpringModel() {
    Model providerModel = mock(Model.class);

    context.withBean(Model.class, () -> providerModel).run(application -> {
      AgentScopeModelResolver resolver = application.getBean(AgentScopeModelResolver.class);

      assertSame(providerModel, resolver.resolve(AgentScopeModelConfiguration.PRIMARY_MODEL_SLOT));
    });
  }

  @Test
  void startsWithoutCredentialsAndFailsClosedWhenThePrimarySlotIsInvoked() {
    context.run(application -> {
      AgentScopeModelResolver resolver = application.getBean(AgentScopeModelResolver.class);

      assertThrows(
          IllegalStateException.class,
          () -> resolver.resolve(AgentScopeModelConfiguration.PRIMARY_MODEL_SLOT));
    });
  }

  @Test
  void rejectsAnAmbiguousPrimaryProviderBinding() {
    context
        .withBean("firstModel", Model.class, () -> mock(Model.class))
        .withBean("secondModel", Model.class, () -> mock(Model.class))
        .run(application -> {
          AgentScopeModelResolver resolver = application.getBean(AgentScopeModelResolver.class);

          assertThrows(
              IllegalStateException.class,
              () -> resolver.resolve(AgentScopeModelConfiguration.PRIMARY_MODEL_SLOT));
        });
  }

  @Test
  void resolvesTheModelCreatedByTheAgentScopeOpenAiStarter() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpenAIAutoConfiguration.class))
        .withUserConfiguration(AgentScopeModelConfiguration.class)
        .withPropertyValues(
            "agentscope.model.provider=openai",
            "agentscope.openai.api-key=test-only-key",
            "agentscope.openai.model-name=gpt-q03-test")
        .run(application -> {
          assertThat(application).hasNotFailed().hasSingleBean(Model.class);
          AgentScopeModelResolver resolver = application.getBean(AgentScopeModelResolver.class);

          assertSame(
              application.getBean(Model.class),
              resolver.resolve(AgentScopeModelConfiguration.PRIMARY_MODEL_SLOT));
        });
  }

  @Test
  void usesAgentScopeStructuredOutputFallbackForTheOfficialDeepSeekEndpoint() {
    modelContext("https://api.deepseek.com", "deepseek-v4-flash").run(application -> {
      assertThat(application).hasNotFailed().hasSingleBean(Model.class);

      Model model = application.getBean(Model.class);
      assertThat(model.supportsNativeStructuredOutput()).isFalse();
      assertThat(model.supportsNativeStructuredOutputWithTools()).isFalse();
    });
  }

  @Test
  void preservesNativeStructuredOutputWithToolsForOpenAi() {
    modelContext("https://api.openai.com/v1", "gpt-q03-test").run(application -> {
      assertThat(application).hasNotFailed().hasSingleBean(Model.class);

      Model model = application.getBean(Model.class);
      assertThat(model.supportsNativeStructuredOutput()).isTrue();
      assertThat(model.supportsNativeStructuredOutputWithTools()).isTrue();
    });
  }

  @Test
  void recognizesOnlyTheOfficialCredentialFreeDeepSeekEndpoint() {
    assertThat(AgentScopeModelConfiguration.isOfficialDeepSeekEndpoint(
        "https://api.deepseek.com")).isTrue();
    assertThat(AgentScopeModelConfiguration.isOfficialDeepSeekEndpoint(
        "https://api.deepseek.com/v1")).isTrue();
    assertThat(AgentScopeModelConfiguration.isOfficialDeepSeekEndpoint(
        "http://api.deepseek.com")).isFalse();
    assertThat(AgentScopeModelConfiguration.isOfficialDeepSeekEndpoint(
        "https://api.deepseek.com.example.org")).isFalse();
    assertThat(AgentScopeModelConfiguration.isOfficialDeepSeekEndpoint(
        "https://key@api.deepseek.com")).isFalse();
  }

  private ApplicationContextRunner modelContext(String baseUrl, String modelName) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpenAIAutoConfiguration.class))
        .withUserConfiguration(AgentScopeModelConfiguration.class)
        .withPropertyValues(
            "agentscope.model.provider=openai",
            "agentscope.openai.api-key=test-only-key",
            "agentscope.openai.model-name=" + modelName,
            "agentscope.openai.base-url=" + baseUrl);
  }
}
