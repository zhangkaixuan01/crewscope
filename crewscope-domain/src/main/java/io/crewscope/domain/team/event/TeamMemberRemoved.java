package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after an administrative removal closed a membership and revoked its grants. */
public record TeamMemberRemoved(
    UUID memberId, UUID userPrincipalId, long authorizationVersionAfter) implements DomainEvent {

  public TeamMemberRemoved {
    memberId = Objects.requireNonNull(memberId, "memberId");
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
  }

  public static TeamMemberRemoved from(TeamMember member) {
    TeamMember source = Objects.requireNonNull(member, "member");
    return new TeamMemberRemoved(
        source.id().value(), source.userPrincipalId().value(), source.authorizationVersion());
  }
}
