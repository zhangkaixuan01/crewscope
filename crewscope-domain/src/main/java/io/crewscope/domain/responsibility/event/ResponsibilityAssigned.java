package io.crewscope.domain.responsibility.event;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Version 1 fact emitted for Owner, Executor and advisory Reviewer assignments. */
public record ResponsibilityAssigned(
    UUID workItemId,
    ResponsibilityRole role,
    UUID actorPrincipalId,
    Optional<UUID> replacedAssignmentId)
    implements DomainEvent {

  public ResponsibilityAssigned {
    workItemId = AggregateId.requireValue(workItemId, "ResponsibilityAssigned.workItemId");
    role = Objects.requireNonNull(role, "role");
    actorPrincipalId =
        AggregateId.requireValue(actorPrincipalId, "ResponsibilityAssigned.actorPrincipalId");
    replacedAssignmentId = Objects.requireNonNull(replacedAssignmentId, "replacedAssignmentId");
  }

  public static ResponsibilityAssigned from(
      ResponsibilityAssignment assignment,
      Optional<ResponsibilityAssignmentId> replacedAssignmentId) {
    ResponsibilityAssignment source = Objects.requireNonNull(assignment, "assignment");
    return new ResponsibilityAssigned(
        source.workItemId().value(),
        source.role(),
        source.actorPrincipalId().value(),
        Objects.requireNonNull(replacedAssignmentId, "replacedAssignmentId")
            .map(ResponsibilityAssignmentId::value));
  }
}
