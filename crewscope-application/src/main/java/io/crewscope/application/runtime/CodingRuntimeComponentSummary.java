package io.crewscope.application.runtime;

import java.util.Objects;

/** Identifier-free health counts for local Sandbox or Diff Watcher resources. */
public record CodingRuntimeComponentSummary(
        CodingRuntimeComponentHealth health, int total, int healthy, int failed) {

    public CodingRuntimeComponentSummary {
        health = Objects.requireNonNull(health, "health");
        if (total < 0 || healthy < 0 || failed < 0 || healthy + failed != total) {
            throw new IllegalArgumentException("component counts must be non-negative and closed");
        }
    }
}
