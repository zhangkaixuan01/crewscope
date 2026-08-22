package io.crewscope.server.config.application;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.spring.boot.openai.OpenAIChatModelBuilderCustomizer;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.crewscope.agentscope.AgentScopeModelResolver;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Connects CrewScope's stable model slot to the provider Model created by AgentScope starters. */
@Configuration(proxyBeanMethods = false)
public class AgentScopeModelConfiguration {

  public static final String PRIMARY_MODEL_SLOT = "crewscope-primary";

  private static final String DEEPSEEK_API_HOST = "api.deepseek.com";

  @Bean
  OpenAIChatModelBuilderCustomizer deepSeekStructuredOutputCompatibilityCustomizer(
      Environment environment) {
    boolean deepSeekEndpoint = isOfficialDeepSeekEndpoint(
        environment.getProperty("agentscope.openai.base-url"));
    return builder -> {
      if (deepSeekEndpoint) {
        // DeepSeek has stricter message/tool schemas and does not expose OpenAI json_schema.
        // AgentScope's fallback keeps tools available and validates the final structured result.
        builder.formatter(new DeepSeekFormatter());
        builder.nativeStructuredOutput(false);
        builder.nativeStructuredOutputWithTools(false);
      }
    };
  }

  @Bean
  AgentScopeModelResolver agentScopeModelResolver(ObjectProvider<Model> configuredModels) {
    return modelId -> {
      if (!PRIMARY_MODEL_SLOT.equals(modelId)) {
        return ModelRegistry.resolve(modelId);
      }
      // Provider starters publish a Spring Bean and do not register the stable CrewScope alias in
      // ModelRegistry. Resolve lazily so server-only startup remains possible without credentials,
      // while the first Agent call fails closed when no unique provider Model is configured.
      Model configured = configuredModels.getIfUnique();
      if (configured == null) {
        throw new IllegalStateException(
            "crewscope-primary requires exactly one configured AgentScope Model bean");
      }
      return configured;
    };
  }

  static boolean isOfficialDeepSeekEndpoint(String rawBaseUrl) {
    if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
      return false;
    }
    try {
      URI endpoint = URI.create(rawBaseUrl.trim());
      return "https".equalsIgnoreCase(endpoint.getScheme())
          && DEEPSEEK_API_HOST.equalsIgnoreCase(endpoint.getHost())
          && endpoint.getUserInfo() == null;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
