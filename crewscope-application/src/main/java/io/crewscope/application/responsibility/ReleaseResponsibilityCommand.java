package io.crewscope.application.responsibility;

/** Releases one non-Owner responsibility using its committed version. */
public record ReleaseResponsibilityCommand(long expectedVersion) {

  public ReleaseResponsibilityCommand {
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
