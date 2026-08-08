package io.crewscope.application.team;

import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Untrusted locator for the USER Principal that will join one Team. */
public record AddTeamMemberCommand(PrincipalId userPrincipalId) {

  public AddTeamMemberCommand {
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
  }
}
