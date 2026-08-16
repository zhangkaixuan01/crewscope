package io.crewscope.evaluation;

import java.util.List;

/** Validates security-sensitive Coding Sandbox configuration. */
public record CodingProperties(boolean networkEnabled, List<String> allowedPaths, int commandTimeoutSeconds) {

  public CodingProperties {
    allowedPaths = List.copyOf(allowedPaths);
  }
}
