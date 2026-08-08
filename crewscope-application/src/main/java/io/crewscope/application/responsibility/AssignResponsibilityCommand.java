package io.crewscope.application.responsibility;

import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Identifies the server-resolved Principal receiving one responsibility. */
public record AssignResponsibilityCommand(PrincipalId actorPrincipalId) {

  public AssignResponsibilityCommand {
    actorPrincipalId = Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
  }
}
