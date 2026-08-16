package io.crewscope.application.responsibility;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;

/** Responsibility fact enriched with the current Principal display name for management views. */
public record ResponsibilityAssignmentView(
    ResponsibilityAssignment assignment,
    String actorDisplayName,
    Optional<AgentProfileId> actorAgentProfileId) {

  public ResponsibilityAssignmentView {
    assignment = Objects.requireNonNull(assignment, "assignment");
    if (actorDisplayName == null || actorDisplayName.isBlank()) {
      throw new IllegalArgumentException("actorDisplayName must not be blank");
    }
    actorDisplayName = actorDisplayName.strip();
    actorAgentProfileId = Objects.requireNonNull(
        actorAgentProfileId, "actorAgentProfileId");
    if (!assignment.actorType().isAgent() && actorAgentProfileId.isPresent()) {
      throw new IllegalArgumentException("Only Agent responsibilities can expose an AgentProfile");
    }
  }
}
