package io.crewscope.application.task;

import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;

/**
 * Assign-and-start instruction for one delegation command: the selected Agent becomes the active
 * EXECUTOR in the same transaction that creates the Task. The target must be the same Agent the
 * execution selects, so a single recoverable command never diverges between assignment and start.
 */
public record ExecutorAssignmentInstruction(AgentProfileId agentProfileId) {

  public ExecutorAssignmentInstruction {
    agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
  }
}
