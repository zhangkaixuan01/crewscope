package io.crewscope.evaluation;

import java.nio.file.Path;

/** Resolves an Agent path under its authorized repository subtree. */
public final class RepositoryPathPolicy {

  private final Path allowedRoot;

  public RepositoryPathPolicy(Path allowedRoot) {
    this.allowedRoot = allowedRoot.toAbsolutePath().normalize();
  }

  public Path resolve(String candidate) {
    Path resolved = allowedRoot.resolve(candidate).normalize();
    if (!resolved.startsWith(allowedRoot)) {
      throw new IllegalArgumentException("Path is outside AllowedPaths");
    }
    return resolved;
  }
}
