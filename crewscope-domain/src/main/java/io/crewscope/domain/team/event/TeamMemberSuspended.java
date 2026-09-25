package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after a member lost participation through administrative suspension. */
public record TeamMemberSuspended(
    UUID memberId, UUID userPrincipalId, long authorizationVersionAfter) implements DomainEvent {

  public TeamMemberSuspended {
    memberId = Objects.requireNonNull(memberId, "memberId");
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
  }

  public static TeamMemberSuspended from(TeamMember member) {
    TeamMember source = Objects.requireNonNull(member, "member");
    return new TeamMemberSuspended(
        source.id().value(), source.userPrincipalId().value(), source.authorizationVersion());
  }
}
