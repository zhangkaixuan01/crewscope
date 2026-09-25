package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/**
 * Fact emitted after ownership moved to another active member. The previous owner's TEAM_OWNER
 * grant is revoked in the same transaction; authorizationVersionAfter reports the new owner's
 * authorization dimension after the grant.
 */
public record TeamOwnershipTransferred(
    UUID fromMemberId, UUID toMemberId, long authorizationVersionAfter) implements DomainEvent {

  public TeamOwnershipTransferred {
    fromMemberId = Objects.requireNonNull(fromMemberId, "fromMemberId");
    toMemberId = Objects.requireNonNull(toMemberId, "toMemberId");
  }
}
