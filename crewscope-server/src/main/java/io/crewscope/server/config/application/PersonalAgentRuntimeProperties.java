package io.crewscope.server.config.application;

import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-backed default runtime policy used by version-pinned Personal Agent profiles. */
@ConfigurationProperties(prefix = "crewscope.runtime.personal-agent")
public class PersonalAgentRuntimeProperties {

  private String modelId = "crewscope-primary";
  private String fallbackModelId;
  private String systemPrompt =
      "You are the member's Personal Agent in CrewScope. Collaborate clearly and only use "
          + "server-authorized tools and context.";
  private int maxIterations = 20;
  private int maxRetries = 3;
  private Path runtimeRoot = Path.of("./var/crewscope/agent-runtime");

  public String getModelId() {
    return modelId;
  }

  public void setModelId(String modelId) {
    this.modelId = modelId;
  }

  public Optional<String> fallbackModelId() {
    return Optional.ofNullable(fallbackModelId).filter(value -> !value.isBlank());
  }

  public String getFallbackModelId() {
    return fallbackModelId;
  }

  public void setFallbackModelId(String fallbackModelId) {
    this.fallbackModelId = fallbackModelId;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public void setSystemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
  }

  public int getMaxIterations() {
    return maxIterations;
  }

  public void setMaxIterations(int maxIterations) {
    this.maxIterations = maxIterations;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public Path getRuntimeRoot() {
    return runtimeRoot;
  }

  public void setRuntimeRoot(Path runtimeRoot) {
    this.runtimeRoot = runtimeRoot;
  }
}
