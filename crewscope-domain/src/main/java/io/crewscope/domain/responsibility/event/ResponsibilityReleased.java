package io.crewscope.domain.responsibility.event;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted when an Executor or Reviewer assignment is released. */
public record ResponsibilityReleased(
    UUID workItemId, ResponsibilityRole role, UUID actorPrincipalId) implements DomainEvent {

  public ResponsibilityReleased {
    workItemId = AggregateId.requireValue(workItemId, "ResponsibilityReleased.workItemId");
    role = Objects.requireNonNull(role, "role");
    actorPrincipalId =
        AggregateId.requireValue(actorPrincipalId, "ResponsibilityReleased.actorPrincipalId");
  }

  public static ResponsibilityReleased from(ResponsibilityAssignment assignment) {
    ResponsibilityAssignment source = Objects.requireNonNull(assignment, "assignment");
    return new ResponsibilityReleased(
        source.workItemId().value(), source.role(), source.actorPrincipalId().value());
  }
}
