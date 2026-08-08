package io.crewscope.application.responsibility;

import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Replaces the active Owner using an ABA-safe expected assignment identity and version. */
public record ReplaceOwnerCommand(
    PrincipalId actorPrincipalId, ActiveOwnerExpectation expectation) {

  public ReplaceOwnerCommand {
    actorPrincipalId = Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
    expectation = Objects.requireNonNull(expectation, "expectation");
  }
}
