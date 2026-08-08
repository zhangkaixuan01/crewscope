package io.crewscope.application.team;

import io.crewscope.domain.identity.Principal;
import java.util.Objects;

/** Server-resolved identity and platform authority for one Team request. */
public record TeamAccessContext(Principal actor, boolean platformAdministrator) {

  public TeamAccessContext {
    actor = Objects.requireNonNull(actor, "actor");
  }
}
