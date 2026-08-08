package io.crewscope.application.responsibility;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import java.util.Objects;

/** Responsibility fact enriched with the current Principal display name for management views. */
public record ResponsibilityAssignmentView(
    ResponsibilityAssignment assignment, String actorDisplayName) {

  public ResponsibilityAssignmentView {
    assignment = Objects.requireNonNull(assignment, "assignment");
    if (actorDisplayName == null || actorDisplayName.isBlank()) {
      throw new IllegalArgumentException("actorDisplayName must not be blank");
    }
    actorDisplayName = actorDisplayName.strip();
  }
}
