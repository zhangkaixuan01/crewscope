package io.crewscope.application.team;

import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Explicit owner selection for completing one migrated Team foundation. */
public record CompleteTeamInitializationCommand(PrincipalId ownerPrincipalId) {

  public CompleteTeamInitializationCommand {
    ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
  }
}
