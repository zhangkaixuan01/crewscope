package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after a suspended or previously left member regained participation. */
public record TeamMemberActivated(
    UUID memberId, UUID userPrincipalId, long authorizationVersionAfter) implements DomainEvent {

  public TeamMemberActivated {
    memberId = Objects.requireNonNull(memberId, "memberId");
    userPrincipalId = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
  }

  public static TeamMemberActivated from(TeamMember member) {
    TeamMember source = Objects.requireNonNull(member, "member");
    return new TeamMemberActivated(
        source.id().value(), source.userPrincipalId().value(), source.authorizationVersion());
  }
}
