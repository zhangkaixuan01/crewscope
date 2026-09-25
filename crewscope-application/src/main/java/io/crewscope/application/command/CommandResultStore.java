package io.crewscope.application.command;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Optional;

/** Result coordinates share the receipt transaction and retention policy. */
public interface CommandResultStore {
  /** Inserts the first result after receipt completion, never updates or guesses an old result. */
  void saveResult(CommandResult result);

  /** Actor filtering happens before disclosure; object authorization must still be rechecked. */
  Optional<CommandResult> findResult(
      OrganizationId organizationId, IdempotencyKey key, PrincipalId actorId);
}
