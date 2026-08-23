package io.crewscope.server.config.application;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded process-level defaults for short-lived Template-backed Agent instances. */
@ConfigurationProperties(prefix = "crewscope.runtime.template-agent")
public class TemplateAgentRuntimeProperties {

  private Path runtimeRoot = Path.of("./var/crewscope/template-agent-runtime");
  private int maximumIterations = 40;

  public Path getRuntimeRoot() {
    return runtimeRoot;
  }

  public void setRuntimeRoot(Path runtimeRoot) {
    this.runtimeRoot = runtimeRoot;
  }

  public int getMaximumIterations() {
    return maximumIterations;
  }

  public void setMaximumIterations(int maximumIterations) {
    this.maximumIterations = maximumIterations;
  }

  /** Returns a normalized path without allowing an empty runtime root. */
  public Path validatedRuntimeRoot() {
    if (runtimeRoot == null || runtimeRoot.toString().isBlank()) {
      throw new IllegalStateException("Template Agent runtime root must not be empty");
    }
    return runtimeRoot.toAbsolutePath().normalize();
  }

  /** Keeps an individual Agent loop finite even when a Template contains hostile instructions. */
  public int validatedMaximumIterations() {
    if (maximumIterations < 1 || maximumIterations > 200) {
      throw new IllegalStateException(
          "Template Agent maximum iterations must be between 1 and 200");
    }
    return maximumIterations;
  }
}
